import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecsPatterns from 'aws-cdk-lib/aws-ecs-patterns';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as route53Targets from 'aws-cdk-lib/aws-route53-targets';
import * as wafv2 from 'aws-cdk-lib/aws-wafv2';
import { DockerImageAsset, Platform } from 'aws-cdk-lib/aws-ecr-assets';

export interface BroadWorksMcpStackProps extends cdk.StackProps {
  /**
   * Public DNS hostname (e.g. mcp.example.com) used to build the server's base URL and to
   * provision the ACM certificate for the HTTPS ALB listener. May also be supplied via the
   * `hostname` CDK context value.
   */
  readonly hostname?: string;
  /**
   * ARN of an existing ACM certificate for the HTTPS ALB listener. When omitted, a certificate is
   * created from {@link hostname}. Provide this to reuse a pre-validated certificate instead.
   */
  readonly certificateArn?: string;
  /**
   * Name of the Route 53 public hosted zone that owns {@link hostname} (e.g. example.com). Used to
   * create the DNS alias record for the ALB and to DNS-validate the ACM certificate automatically.
   * When omitted, it is derived from {@link hostname} by stripping the leftmost label
   * (mcp.example.com -> example.com). May also be supplied via the `hostedZoneName` context value.
   */
  readonly hostedZoneName?: string;
  /**
   * ID of the Route 53 hosted zone named {@link hostedZoneName}. When both are provided the zone is
   * referenced directly (no lookup); otherwise the zone is discovered via a context lookup. May also
   * be supplied via the `hostedZoneId` context value.
   */
  readonly hostedZoneId?: string;
  /**
   * Development-only opt-out that allows the ALB to listen on plain HTTP when neither
   * {@link certificateArn} nor {@link hostname} is supplied. Defaults to false: synthesis fails
   * rather than silently deploying an unencrypted public listener. May also be supplied via the
   * `allowInsecureHttp` CDK context value.
   */
  readonly allowInsecureHttp?: boolean;
}

/**
 * Deploys broadworks-mcp on ECS Fargate behind an (HTTPS) Application Load Balancer, with two
 * DynamoDB tables encrypted by a customer-managed KMS key, a task IAM role granting scoped
 * KMS + DynamoDB access (the blueprint's "IRSA" role), and SSM SecureString-backed secrets.
 */
export class BroadWorksMcpStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: BroadWorksMcpStackProps = {}) {
    super(scope, id, props);

    const applicationId = this.node.tryGetContext('applicationId') ?? process.env.APPLICATION_ID ?? 'broadworks-mcp';
    const oauthRedirectAllowlist =
      this.node.tryGetContext('oauthRedirectAllowlist') ?? process.env.OAUTH_REDIRECT_ALLOWLIST ?? '';
    const ssmNames = this.node.tryGetContext('ssm') ?? {};

    // Public hostname drives both the app's base URL (https://<hostname>) and the ACM certificate.
    const hostname: string | undefined = props.hostname ?? this.node.tryGetContext('hostname');

    // Route 53 hosted zone that owns the hostname; used to create the DNS alias record and to
    // DNS-validate the certificate. When not given explicitly, derive it from the hostname by
    // dropping the leftmost label (mcp.example.com -> example.com).
    const hostedZoneId: string | undefined = props.hostedZoneId ?? this.node.tryGetContext('hostedZoneId');
    const hostedZoneName: string | undefined =
      props.hostedZoneName ??
      this.node.tryGetContext('hostedZoneName') ??
      (hostname && hostname.includes('.') ? hostname.substring(hostname.indexOf('.') + 1) : undefined);

    // Resolve the hosted zone up front so it can drive both certificate validation and the alias
    // record. If an id + name pair is supplied, reference it directly; otherwise look it up.
    let hostedZone: route53.IHostedZone | undefined;
    if (hostname && hostedZoneName) {
      hostedZone =
        hostedZoneId !== undefined
          ? route53.HostedZone.fromHostedZoneAttributes(this, 'HostedZone', {
              hostedZoneId,
              zoneName: hostedZoneName,
            })
          : route53.HostedZone.fromLookup(this, 'HostedZone', { domainName: hostedZoneName });
    }

    // ---- Networking -------------------------------------------------------
    // Allocate a fixed Elastic IP for the NAT gateway so the ECS tasks' outbound public IP is
    // stable across deploys and NAT gateway replacements. Downstream systems (e.g. the BroadWorks
    // OCI/provisioning endpoints) can then safely allowlist this single, unchanging address.
    const natEip = new ec2.CfnEIP(this, 'NatGatewayEip', {
      domain: 'vpc',
      tags: [{ key: 'Name', value: 'broadworks-mcp-nat' }],
    });

    // Provision the VPC's NAT gateway with the fixed Elastic IP above (one EIP per NAT gateway).
    const natGatewayProvider = ec2.NatProvider.gateway({
      eipAllocationIds: [natEip.attrAllocationId],
    });

    // Two-tier subnet layout: public subnets host the NAT gateway (and the internet-facing ALB),
    // while the ECS Fargate tasks live in private subnets whose only route to the internet is
    // through the NAT gateway. This keeps the tasks unreachable from the public internet while
    // still allowing outbound traffic via the fixed Elastic IP.
    const vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 2,
      natGateways: 1,
      natGatewayProvider,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          name: 'private',
          subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
          cidrMask: 24,
        },
      ],
    });

    // ---- Customer-managed KMS key (secret encryption at rest) -------------
    const dataKey = new kms.Key(this, 'DataKey', {
      alias: 'alias/broadworks-mcp',
      enableKeyRotation: true,
      description: 'broadworks-mcp: encrypts DynamoDB tables and per-user BroadWorks secrets',
    });

    // ---- DynamoDB tables --------------------------------------------------
    const sessionsTable = new dynamodb.Table(this, 'SessionsTable', {
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      encryption: dynamodb.TableEncryption.CUSTOMER_MANAGED,
      encryptionKey: dataKey,
      timeToLiveAttribute: 'ttl',
      pointInTimeRecovery: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    sessionsTable.addGlobalSecondaryIndex({
      indexName: 'refresh-index',
      partitionKey: { name: 'refreshToken', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.ALL,
    });

    const userConfigTable = new dynamodb.Table(this, 'UserConfigTable', {
      partitionKey: { name: 'applicationId', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      encryption: dynamodb.TableEncryption.CUSTOMER_MANAGED,
      encryptionKey: dataKey,
      pointInTimeRecovery: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // ---- Task role (blueprint "IRSA") -------------------------------------
    const taskRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'broadworks-mcp task role: scoped DynamoDB + KMS access',
    });

    // ---- Secrets from SSM SecureString ------------------------------------
    const googleClientId = ssm.StringParameter.fromSecureStringParameterAttributes(this, 'GoogleClientId', {
      parameterName: ssmNames.googleClientId ?? '/broadworks-mcp/google-client-id',
    });
    const googleClientSecret = ssm.StringParameter.fromSecureStringParameterAttributes(this, 'GoogleClientSecret', {
      parameterName: ssmNames.googleClientSecret ?? '/broadworks-mcp/google-client-secret',
    });
    const alpacaLicenseKey = ssm.StringParameter.fromSecureStringParameterAttributes(this, 'AlpacaLicenseKey', {
      parameterName: ssmNames.alpacaLicenseKey ?? '/broadworks-mcp/alpaca-license-key',
    });

    // Live BroadWorks OCI login is the app default (true). Override via context `alpacaLive` or
    // env ALPACA_LIVE when synthesizing only if you need to force it off in a deployment.
    const alpacaLive: string =
      this.node.tryGetContext('alpacaLive') ?? process.env.ALPACA_LIVE ?? 'true';

    // ---- Container image (built from the repo root Dockerfile) ------------
    // Pin the build platform to linux/amd64 so the asset is built for the same
    // architecture the Fargate task runs on (see runtimePlatform below). Without
    // this, building on an arm64 host (e.g. Apple Silicon) produces an arm64
    // image that Fargate's default X86_64 runtime cannot execute, failing at
    // startup with "exec /usr/bin/sh: exec format error".
    // The asset is created explicitly (rather than via ContainerImage.fromAsset) so the app
    // container and the volume-init container below share a single build/publish of the image.
    const imageAsset = new DockerImageAsset(this, 'ImageAsset', {
      directory: path.join(__dirname, '..', '..'),
      file: 'Dockerfile',
      platform: Platform.LINUX_AMD64,
    });
    const image = ecs.ContainerImage.fromDockerImageAsset(imageAsset);

    // Let CloudWatch Logs use the customer-managed key for the log group below. The encryption
    // context condition scopes the grant to log groups in this account/region.
    dataKey.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'AllowCloudWatchLogs',
        principals: [new iam.ServicePrincipal(`logs.${this.region}.amazonaws.com`)],
        actions: [
          'kms:Encrypt*',
          'kms:Decrypt*',
          'kms:ReEncrypt*',
          'kms:GenerateDataKey*',
          'kms:Describe*',
        ],
        resources: ['*'],
        conditions: {
          ArnLike: {
            'kms:EncryptionContext:aws:logs:arn': `arn:${this.partition}:logs:${this.region}:${this.account}:log-group:*`,
          },
        },
      }),
    );

    const logGroup = new logs.LogGroup(this, 'LogGroup', {
      retention: logs.RetentionDays.ONE_MONTH,
      encryptionKey: dataKey,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, containerInsights: true });

    // TLS is mandatory. Plain HTTP is only possible behind an explicit development opt-out.
    const allowInsecureHttp: boolean =
      props.allowInsecureHttp ?? String(this.node.tryGetContext('allowInsecureHttp') ?? 'false') === 'true';

    // Prefer an explicitly provided certificate ARN; otherwise create a certificate from the
    // hostname (DNS-validated).
    let certificate: acm.ICertificate | undefined;
    if (props.certificateArn) {
      certificate = acm.Certificate.fromCertificateArn(this, 'Certificate', props.certificateArn);
    } else if (hostname) {
      certificate = new acm.Certificate(this, 'Certificate', {
        domainName: hostname,
        // When the hosted zone is known, CDK writes the DNS validation records automatically;
        // otherwise the validation CNAMEs must be added to DNS manually.
        validation: hostedZone
          ? acm.CertificateValidation.fromDns(hostedZone)
          : acm.CertificateValidation.fromDns(),
      });
    } else if (!allowInsecureHttp) {
      throw new Error(
        'HTTPS is required: provide -c hostname=mcp.example.com (to create an ACM certificate) or ' +
          '-c certificateArn=arn:aws:acm:<region>:<acct>:certificate/<id>. For local/dev only, opt out ' +
          'of TLS with -c allowInsecureHttp=true.',
      );
    }

    // ---- Fargate service behind an ALB ------------------------------------
    const service = new ecsPatterns.ApplicationLoadBalancedFargateService(this, 'Service', {
      cluster,
      cpu: 512,
      memoryLimitMiB: 1024,
      desiredCount: 2,
      // Run on X86_64/Linux to match the amd64 image built above.
      runtimePlatform: {
        cpuArchitecture: ecs.CpuArchitecture.X86_64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
      publicLoadBalancer: true,
      // Run the tasks in the private subnets with no public IP so their only outbound path is
      // through the NAT gateway (and its fixed Elastic IP). The public ALB still reaches them via
      // the VPC-internal target group.
      taskSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      assignPublicIp: false,
      protocol: certificate ? elbv2.ApplicationProtocol.HTTPS : elbv2.ApplicationProtocol.HTTP,
      certificate,
      // With HTTPS, also open :80 purely to 301-redirect clients to :443.
      redirectHTTP: certificate !== undefined,
      taskImageOptions: {
        image,
        containerName: 'broadworks-mcp',
        containerPort: 8080,
        taskRole,
        enableLogging: true,
        logDriver: ecs.LogDrivers.awsLogs({ streamPrefix: 'broadworks-mcp', logGroup }),
        environment: {
          STORAGE_BACKEND: 'DYNAMODB',
          SESSION_TABLE: sessionsTable.tableName,
          USER_CONFIG_TABLE: userConfigTable.tableName,
          APPLICATION_ID: applicationId,
          KMS_KEY_ID: dataKey.keyId,
          AWS_REGION: this.region,
          OAUTH_REDIRECT_ALLOWLIST: oauthRedirectAllowlist,
          PUBLIC_HOSTNAME: hostname ?? '',
          // Explicit live flag (app default is already true; false only for unusual deploys).
          ALPACA_LIVE: alpacaLive,
        },
        secrets: {
          GOOGLE_CLIENT_ID: ecs.Secret.fromSsmParameter(googleClientId),
          GOOGLE_CLIENT_SECRET: ecs.Secret.fromSsmParameter(googleClientSecret),
          ALPACA_LICENSE_KEY: ecs.Secret.fromSsmParameter(alpacaLicenseKey),
        },
      },
    });

    // ---- Container hardening ----------------------------------------------
    // Immutable root filesystem; everything the JVM writes to is an explicit ephemeral volume:
    // /app/.cache holds the JCS disk cache (cache.ccf DiskPath=.cache/jcs) and /tmp the JVM's
    // temp/hsperfdata files (Tomcat's tempDir, hsperfdata, ...).
    const taskDefinition = service.taskDefinition;
    taskDefinition.addVolume({ name: 'app-cache' });
    taskDefinition.addVolume({ name: 'tmp' });
    taskDefinition.defaultContainer!.addMountPoints(
      { sourceVolume: 'app-cache', containerPath: '/app/.cache', readOnly: false },
      { sourceVolume: 'tmp', containerPath: '/tmp', readOnly: false },
    );

    // Fargate creates the ephemeral volumes above empty and owned by root:root 0755 — the image's
    // ownership/permissions for /tmp and /app/.cache are NOT carried over into the mount. Since the
    // app container runs as the unprivileged uid 10001 (see Dockerfile), the JVM would fail at
    // startup with "Unable to create tempDir. java.io.tmpdir is set to /tmp". A short-lived root
    // init container therefore fixes up ownership/permissions on both mounts before the app starts.
    const volumeInit = taskDefinition.addContainer('VolumeInit', {
      image,
      containerName: 'volume-init',
      user: 'root',
      // Not part of the serving workload: the task must not be considered unhealthy when it exits.
      essential: false,
      memoryReservationMiB: 64,
      entryPoint: ['sh', '-c'],
      command: [
        'set -e; ' +
          'mkdir -p /app/.cache/jcs; ' +
          'chown -R 10001:10001 /app/.cache; ' +
          'chown 10001:10001 /tmp; ' +
          'chmod 1777 /tmp',
      ],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'volume-init', logGroup }),
    });
    volumeInit.addMountPoints(
      { sourceVolume: 'app-cache', containerPath: '/app/.cache', readOnly: false },
      { sourceVolume: 'tmp', containerPath: '/tmp', readOnly: false },
    );
    // Hold the app container back until the fix-up has completed successfully.
    taskDefinition.defaultContainer!.addContainerDependencies({
      container: volumeInit,
      condition: ecs.ContainerDependencyCondition.SUCCESS,
    });

    // ReadonlyRootFilesystem is not exposed by the L2 container definition props, so it is applied
    // through an escape hatch. Both containers only ever write to the mounted volumes above, hence
    // every container definition (index 0 = the app, index 1 = volume-init, in creation order) gets
    // an immutable root filesystem.
    const cfnTaskDefinition = taskDefinition.node.defaultChild as ecs.CfnTaskDefinition;
    for (const index of [0, 1]) {
      cfnTaskDefinition.addPropertyOverride(`ContainerDefinitions.${index}.ReadonlyRootFilesystem`, true);
    }

    // ---- WAF (internet-facing ALB) ----------------------------------------
    const webAcl = new wafv2.CfnWebACL(this, 'WebAcl', {
      name: 'broadworks-mcp-web-acl',
      scope: 'REGIONAL',
      defaultAction: { allow: {} },
      visibilityConfig: {
        cloudWatchMetricsEnabled: true,
        sampledRequestsEnabled: true,
        metricName: 'broadworks-mcp-web-acl',
      },
      rules: [
        {
          name: 'AWSManagedRulesCommonRuleSet',
          priority: 0,
          overrideAction: { none: {} },
          statement: {
            managedRuleGroupStatement: { vendorName: 'AWS', name: 'AWSManagedRulesCommonRuleSet' },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            sampledRequestsEnabled: true,
            metricName: 'CommonRuleSet',
          },
        },
        {
          name: 'AWSManagedRulesKnownBadInputsRuleSet',
          priority: 1,
          overrideAction: { none: {} },
          statement: {
            managedRuleGroupStatement: {
              vendorName: 'AWS',
              name: 'AWSManagedRulesKnownBadInputsRuleSet',
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            sampledRequestsEnabled: true,
            metricName: 'KnownBadInputs',
          },
        },
        {
          // Dynamic Client Registration is unauthenticated: 100 requests / 5 min / IP.
          name: 'RateLimitOauthRegister',
          priority: 2,
          action: { block: {} },
          statement: {
            rateBasedStatement: {
              limit: 100,
              evaluationWindowSec: 300,
              aggregateKeyType: 'IP',
              scopeDownStatement: {
                byteMatchStatement: {
                  searchString: '/oauth/register',
                  fieldToMatch: { uriPath: {} },
                  positionalConstraint: 'STARTS_WITH',
                  textTransformations: [{ priority: 0, type: 'LOWERCASE' }],
                },
              },
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            sampledRequestsEnabled: true,
            metricName: 'RateLimitOauthRegister',
          },
        },
        {
          // Token endpoint (code exchange + refresh): 100 requests / 5 min / IP.
          name: 'RateLimitOauthToken',
          priority: 3,
          action: { block: {} },
          statement: {
            rateBasedStatement: {
              limit: 100,
              evaluationWindowSec: 300,
              aggregateKeyType: 'IP',
              scopeDownStatement: {
                byteMatchStatement: {
                  searchString: '/oauth2/token',
                  fieldToMatch: { uriPath: {} },
                  positionalConstraint: 'STARTS_WITH',
                  textTransformations: [{ priority: 0, type: 'LOWERCASE' }],
                },
              },
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            sampledRequestsEnabled: true,
            metricName: 'RateLimitOauthToken',
          },
        },
        {
          name: 'RateLimitGeneral',
          priority: 4,
          action: { block: {} },
          statement: {
            rateBasedStatement: {
              limit: 2000,
              evaluationWindowSec: 300,
              aggregateKeyType: 'IP',
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            sampledRequestsEnabled: true,
            metricName: 'RateLimitGeneral',
          },
        },
      ],
    });

    new wafv2.CfnWebACLAssociation(this, 'WebAclAssociation', {
      resourceArn: service.loadBalancer.loadBalancerArn,
      webAclArn: webAcl.attrArn,
    });

    // Actuator health probe for ALB target group.
    service.targetGroup.configureHealthCheck({
      path: '/actuator/health',
      healthyHttpCodes: '200',
      interval: cdk.Duration.seconds(30),
    });

    // No ALB session stickiness is required. Multi-instance OAuth is durable in DynamoDB:
    // - Interactive Google sign-in HTTP sessions (SecurityContext / saved request) via Spring Session
    //   (HttpSessionConfig / DynamoDbHttpSessionRepository).
    // - SAS authorizations (auth codes, refresh grants) and consents via DynamoDbAuthorizationStore
    //   in the same sessions table (oauth# / oauthtok# / oauthconsent# prefixes).
    // - Issued opaque access-token sessions via DynamoDbSessionStore (including token rotation).
    // Any of the `desiredCount` tasks can therefore serve any step of the
    // `/oauth2/authorization/google` -> `/login/oauth2/code/google` -> `/oauth2/authorize` ->
    // `/oauth2/token` handshake. (Cookie stickiness would not work for native MCP clients, which
    // do not honor the ALB cookie.)

    // ---- DNS records ------------------------------------------------------
    // Point the public hostname at the ALB via Route 53 alias records (IPv4 + IPv6). Requires a
    // resolvable hosted zone; without one the hostname must be wired to the ALB DNS name manually.
    if (hostname && hostedZone) {
      const albAliasTarget = route53.RecordTarget.fromAlias(
        new route53Targets.LoadBalancerTarget(service.loadBalancer),
      );
      new route53.ARecord(this, 'AliasRecord', {
        zone: hostedZone,
        recordName: hostname,
        target: albAliasTarget,
        comment: 'broadworks-mcp: hostname -> ALB (IPv4)',
      });
      new route53.AaaaRecord(this, 'AliasRecordAAAA', {
        zone: hostedZone,
        recordName: hostname,
        target: albAliasTarget,
        comment: 'broadworks-mcp: hostname -> ALB (IPv6)',
      });
    }

    // ---- Grants (least privilege) -----------------------------------------
    sessionsTable.grantReadWriteData(taskRole);
    userConfigTable.grantReadWriteData(taskRole);
    dataKey.grantEncryptDecrypt(taskRole);

    // ---- Outputs ----------------------------------------------------------
    new cdk.CfnOutput(this, 'LoadBalancerDns', {
      value: service.loadBalancer.loadBalancerDnsName,
      description: 'Public DNS name of the MCP load balancer',
    });
    if (hostname && hostedZone) {
      new cdk.CfnOutput(this, 'PublicUrl', {
        value: `https://${hostname}`,
        description: 'Public URL served by the Route 53 alias record pointing at the ALB',
      });
    }
    new cdk.CfnOutput(this, 'NatGatewayEipAddress', {
      value: natEip.ref,
      description: 'Fixed Elastic IP for the NAT gateway (stable outbound public IP of the ECS tasks)',
    });
    new cdk.CfnOutput(this, 'SessionsTableName', { value: sessionsTable.tableName });
    new cdk.CfnOutput(this, 'UserConfigTableName', { value: userConfigTable.tableName });
    new cdk.CfnOutput(this, 'KmsKeyId', { value: dataKey.keyId });

    if (!certificate) {
      cdk.Annotations.of(this).addWarning(
        'allowInsecureHttp is set: the ALB listens on HTTP only. Never use this outside local/dev; provide ' +
          '-c hostname=mcp.example.com (to create a certificate) or -c certificateArn=... for HTTPS.',
      );
    }

    if (hostname && !hostedZone) {
      cdk.Annotations.of(this).addWarning(
        `No Route 53 hosted zone resolved for '${hostname}': DNS alias records were not created and the ` +
          'ACM certificate must be DNS-validated manually. Provide -c hostedZoneName=example.com (and ' +
          'optionally -c hostedZoneId=...) so the infrastructure can create the necessary DNS entries.',
      );
    }
  }
}
