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
import { Platform } from 'aws-cdk-lib/aws-ecr-assets';

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
    const vpc = new ec2.Vpc(this, 'Vpc', { maxAzs: 2, natGateways: 1 });

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

    // ---- Container image (built from the repo root Dockerfile) ------------
    // Pin the build platform to linux/amd64 so the asset is built for the same
    // architecture the Fargate task runs on (see runtimePlatform below). Without
    // this, building on an arm64 host (e.g. Apple Silicon) produces an arm64
    // image that Fargate's default X86_64 runtime cannot execute, failing at
    // startup with "exec /usr/bin/sh: exec format error".
    const image = ecs.ContainerImage.fromAsset(path.join(__dirname, '..', '..'), {
      file: 'Dockerfile',
      platform: Platform.LINUX_AMD64,
    });

    const logGroup = new logs.LogGroup(this, 'LogGroup', {
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, containerInsights: true });

    // Prefer an explicitly provided certificate ARN; otherwise create a certificate from the
    // hostname (DNS-validated). Without either, the ALB falls back to HTTP (development only).
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
      protocol: certificate ? elbv2.ApplicationProtocol.HTTPS : elbv2.ApplicationProtocol.HTTP,
      certificate,
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
        },
        secrets: {
          GOOGLE_CLIENT_ID: ecs.Secret.fromSsmParameter(googleClientId),
          GOOGLE_CLIENT_SECRET: ecs.Secret.fromSsmParameter(googleClientSecret),
        },
      },
    });

    // Actuator health probe for ALB target group.
    service.targetGroup.configureHealthCheck({
      path: '/actuator/health',
      healthyHttpCodes: '200',
      interval: cdk.Duration.seconds(30),
    });

    // Pin each client to a single task for the duration of a session (ALB-generated cookie).
    // The interactive Google sign-in (Spring Security `oauth2Login` + Spring Authorization Server)
    // is stateful: the transient OAuth2 authorization request, the `SecurityContext`, and the saved
    // request are all held in the per-task in-memory HTTP session. With desiredCount > 1 and no
    // stickiness, the ALB round-robins requests, so the `/oauth2/authorization/google` start and the
    // `/login/oauth2/code/google` callback (plus the follow-up redirect back to `/oauth2/authorize`)
    // can land on different tasks. The task handling the callback then has no saved authorization
    // request / authenticated session, so the login fails and the browser is bounced back to Google
    // (observably with `prompt=none`). Sticky sessions keep the whole handshake on one task.
    service.targetGroup.enableCookieStickiness(cdk.Duration.hours(1));

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
    new cdk.CfnOutput(this, 'SessionsTableName', { value: sessionsTable.tableName });
    new cdk.CfnOutput(this, 'UserConfigTableName', { value: userConfigTable.tableName });
    new cdk.CfnOutput(this, 'KmsKeyId', { value: dataKey.keyId });

    if (!certificate) {
      cdk.Annotations.of(this).addWarning(
        'No hostname or certificate provided: the ALB listens on HTTP only. Provide -c hostname=mcp.example.com ' +
          '(to create a certificate) or -c certificateArn=... for HTTPS in production.',
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
