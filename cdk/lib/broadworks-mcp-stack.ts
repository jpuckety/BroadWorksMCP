import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
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
import * as codedeploy from 'aws-cdk-lib/aws-codedeploy';

/** Application container name; must match Dockerfile, appspec.yaml, and CodeDeploy. */
export const APP_CONTAINER_NAME = 'broadworks-mcp';
/** Application container port; must match Dockerfile EXPOSE and appspec.yaml. */
export const APP_CONTAINER_PORT = 8080;
/** ALB / Docker health check path. */
export const APP_HEALTH_CHECK_PATH = '/actuator/health';
export const TASK_FAMILY = 'broadworks-mcp';
export const ECR_REPOSITORY_NAME = 'broadworks-mcp';
export const ECS_CLUSTER_NAME = 'broadworks-mcp';
export const ECS_SERVICE_NAME = 'broadworks-mcp';
export const CODEDEPLOY_APPLICATION_NAME = 'broadworks-mcp';
export const CODEDEPLOY_DEPLOYMENT_GROUP_NAME = 'broadworks-mcp';
export const ECR_PUSH_ROLE_NAME = 'BroadWorksMcpEcrPushRole';
export const PIPELINE_DEPLOY_ROLE_NAME = 'BroadWorksMcpPipelineDeployRole';
/** Public image used until CodeDeploy rolls the real digest. Must provide `sh`/`chown` for volume-init. */
export const PLACEHOLDER_IMAGE = 'public.ecr.aws/amazonlinux/amazonlinux:2023';

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
  /**
   * Pipeline AWS account that may assume {@link ECR_PUSH_ROLE_NAME} and {@link PIPELINE_DEPLOY_ROLE_NAME}.
   * Optional so the stack stays independently deployable for bootstrap / break-glass.
   */
  readonly pipelineAccount?: string;
  /**
   * Initial task-definition image URI. Defaults to {@link PLACEHOLDER_IMAGE}. CodeDeploy owns later
   * image rollouts; do not pass the live service image here after the first create.
   */
  readonly imageUri?: string;
}

/**
 * Deploys broadworks-mcp on ECS Fargate behind an (HTTPS) Application Load Balancer with CodeDeploy
 * blue/green, an ECR repository, two DynamoDB tables encrypted by a customer-managed KMS key, a
 * task IAM role granting scoped KMS + DynamoDB access, and SSM SecureString-backed secrets.
 */
export class BroadWorksMcpStack extends cdk.Stack {
  public readonly repository: ecr.IRepository;
  public readonly cluster: ecs.Cluster;
  public readonly service: ecs.FargateService;
  public readonly loadBalancer: elbv2.ApplicationLoadBalancer;

  constructor(scope: Construct, id: string, props: BroadWorksMcpStackProps = {}) {
    super(scope, id, props);

    const applicationId = this.node.tryGetContext('applicationId') ?? process.env.APPLICATION_ID ?? 'broadworks-mcp';
    const oauthRedirectAllowlist =
      this.node.tryGetContext('oauthRedirectAllowlist') ?? process.env.OAUTH_REDIRECT_ALLOWLIST ?? '';
    const ssmNames = this.node.tryGetContext('ssm') ?? {};

    const hostname: string | undefined = props.hostname ?? this.node.tryGetContext('hostname');
    const pipelineAccount: string | undefined =
      props.pipelineAccount ?? this.node.tryGetContext('pipelineAccount') ?? process.env.PIPELINE_ACCOUNT;
    const imageUri: string =
      props.imageUri ?? this.node.tryGetContext('imageUri') ?? process.env.IMAGE_URI ?? PLACEHOLDER_IMAGE;

    const hostedZoneId: string | undefined = props.hostedZoneId ?? this.node.tryGetContext('hostedZoneId');
    const hostedZoneName: string | undefined =
      props.hostedZoneName ??
      this.node.tryGetContext('hostedZoneName') ??
      (hostname && hostname.includes('.') ? hostname.substring(hostname.indexOf('.') + 1) : undefined);

    let hostedZone: route53.IHostedZone | undefined;
    if (hostname && hostedZoneName) {
      hostedZone = hostedZoneId
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

    const natGatewayProvider = ec2.NatProvider.gateway({
      eipAllocationIds: [natEip.attrAllocationId],
    });

    // Two-tier subnet layout: public subnets host the NAT gateway (and the internet-facing ALB),
    // while the ECS Fargate tasks live in private subnets whose only route to the internet is
    // through the NAT gateway.
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
      pointInTimeRecoverySpecification: {
        pointInTimeRecoveryEnabled: true,
      },
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    sessionsTable.addGlobalSecondaryIndex({
      indexName: 'refresh-index',
      partitionKey: { name: 'refreshToken', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.ALL,
    });

    // Interactive Google-login HTTP sessions (Spring Session). Deliberately a separate table from
    // the OAuth sessions/clients/authorizations above.
    const httpSessionsTable = new dynamodb.Table(this, 'HttpSessionsTable', {
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      encryption: dynamodb.TableEncryption.CUSTOMER_MANAGED,
      encryptionKey: dataKey,
      timeToLiveAttribute: 'ttl',
      pointInTimeRecoverySpecification: {
        pointInTimeRecoveryEnabled: true,
      },
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const userConfigTable = new dynamodb.Table(this, 'UserConfigTable', {
      partitionKey: { name: 'applicationId', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      encryption: dynamodb.TableEncryption.CUSTOMER_MANAGED,
      encryptionKey: dataKey,
      pointInTimeRecoverySpecification: {
        pointInTimeRecoveryEnabled: true,
      },
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

    const alpacaLive: string = this.node.tryGetContext('alpacaLive') ?? process.env.ALPACA_LIVE ?? 'true';

    // ---- ECR (pipeline builds/pushes; CDK does not bake DockerImageAsset) --
    // OrganizationStack / MCPCICD Build may already have created this name.
    this.repository = ecr.Repository.fromRepositoryName(this, 'Repository', ECR_REPOSITORY_NAME);

    const executionRole = new iam.Role(this, 'ExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'broadworks-mcp execution role: logs, SSM secrets, this account ECR only',
    });
    this.repository.grantPull(executionRole);
    executionRole.addToPrincipalPolicy(
      new iam.PolicyStatement({
        actions: ['ecr:GetAuthorizationToken'],
        resources: ['*'],
      }),
    );

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
    logGroup.grantWrite(executionRole);

    this.cluster = new ecs.Cluster(this, 'Cluster', {
      vpc,
      clusterName: ECS_CLUSTER_NAME,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
    });

    const allowInsecureHttp: boolean =
      props.allowInsecureHttp ?? String(this.node.tryGetContext('allowInsecureHttp') ?? 'false') === 'true';

    let certificate: acm.ICertificate | undefined;
    if (props.certificateArn) {
      certificate = acm.Certificate.fromCertificateArn(this, 'Certificate', props.certificateArn);
    } else if (hostname) {
      certificate = new acm.Certificate(this, 'Certificate', {
        domainName: hostname,
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

    const healthCheck: elbv2.HealthCheck = {
      path: APP_HEALTH_CHECK_PATH,
      healthyHttpCodes: '200',
      interval: cdk.Duration.seconds(30),
    };

    this.loadBalancer = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      vpc,
      internetFacing: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    const blueTargetGroup = new elbv2.ApplicationTargetGroup(this, 'BlueTargetGroup', {
      vpc,
      port: APP_CONTAINER_PORT,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targetType: elbv2.TargetType.IP,
      healthCheck,
      deregistrationDelay: cdk.Duration.seconds(30),
    });
    const greenTargetGroup = new elbv2.ApplicationTargetGroup(this, 'GreenTargetGroup', {
      vpc,
      port: APP_CONTAINER_PORT,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targetType: elbv2.TargetType.IP,
      healthCheck,
      deregistrationDelay: cdk.Duration.seconds(30),
    });

    let productionListener: elbv2.ApplicationListener;
    if (certificate) {
      this.loadBalancer.addListener('HttpListener', {
        port: 80,
        protocol: elbv2.ApplicationProtocol.HTTP,
        defaultAction: elbv2.ListenerAction.redirect({
          port: '443',
          protocol: 'HTTPS',
          permanent: true,
        }),
      });
      productionListener = this.loadBalancer.addListener('HttpsListener', {
        port: 443,
        protocol: elbv2.ApplicationProtocol.HTTPS,
        certificates: [certificate],
        defaultAction: elbv2.ListenerAction.forward([blueTargetGroup]),
      });
    } else {
      productionListener = this.loadBalancer.addListener('HttpListener', {
        port: 80,
        protocol: elbv2.ApplicationProtocol.HTTP,
        defaultAction: elbv2.ListenerAction.forward([blueTargetGroup]),
      });
    }

    const image = ecs.ContainerImage.fromRegistry(imageUri);

    const taskDefinition = new ecs.FargateTaskDefinition(this, 'TaskDef', {
      family: TASK_FAMILY,
      cpu: 512,
      memoryLimitMiB: 1024,
      taskRole,
      executionRole,
      runtimePlatform: {
        cpuArchitecture: ecs.CpuArchitecture.X86_64,
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
      },
    });

    const appLogDriver = ecs.LogDrivers.awsLogs({ streamPrefix: 'broadworks-mcp', logGroup });
    const appContainer = taskDefinition.addContainer('App', {
      containerName: APP_CONTAINER_NAME,
      image,
      portMappings: [{ containerPort: APP_CONTAINER_PORT }],
      logging: appLogDriver,
      essential: true,
      environment: {
        STORAGE_BACKEND: 'DYNAMODB',
        SESSION_TABLE: sessionsTable.tableName,
        HTTP_SESSION_TABLE: httpSessionsTable.tableName,
        USER_CONFIG_TABLE: userConfigTable.tableName,
        APPLICATION_ID: applicationId,
        KMS_KEY_ID: dataKey.keyId,
        AWS_REGION: this.region,
        OAUTH_REDIRECT_ALLOWLIST: oauthRedirectAllowlist,
        PUBLIC_HOSTNAME: hostname ?? '',
        ALPACA_LIVE: alpacaLive,
      },
      secrets: {
        GOOGLE_CLIENT_ID: ecs.Secret.fromSsmParameter(googleClientId),
        GOOGLE_CLIENT_SECRET: ecs.Secret.fromSsmParameter(googleClientSecret),
        ALPACA_LICENSE_KEY: ecs.Secret.fromSsmParameter(alpacaLicenseKey),
      },
    });

    taskDefinition.addVolume({ name: 'app-cache' });
    taskDefinition.addVolume({ name: 'tmp' });
    // ECS Exec support: the agent ECS injects into the container writes its state and logs under
    // /var/lib/amazon and /var/log/amazon. With the immutable root filesystem below those writes
    // fail and every `execute-command` session dies immediately, so both directories get their own
    // ephemeral volume.
    taskDefinition.addVolume({ name: 'ssm-agent-state' });
    taskDefinition.addVolume({ name: 'ssm-agent-logs' });
    appContainer.addMountPoints(
      { sourceVolume: 'app-cache', containerPath: '/app/.cache', readOnly: false },
      { sourceVolume: 'tmp', containerPath: '/tmp', readOnly: false },
      { sourceVolume: 'ssm-agent-state', containerPath: '/var/lib/amazon', readOnly: false },
      { sourceVolume: 'ssm-agent-logs', containerPath: '/var/log/amazon', readOnly: false },
    );

    // Fargate creates the ephemeral volumes above empty and owned by root:root 0755. A short-lived
    // root init container fixes up ownership/permissions before the app starts. CodeDeploy replaces
    // only the app container image, so this sidecar stays on the placeholder (which must have sh).
    const volumeInit = taskDefinition.addContainer('VolumeInit', {
      image,
      containerName: 'volume-init',
      user: 'root',
      essential: false,
      memoryReservationMiB: 64,
      entryPoint: ['sh', '-c'],
      command: [
        'set -e; ' +
          'mkdir -p /app/.cache/jcs; ' +
          'chown -R 10001:10001 /app/.cache; ' +
          'chown 10001:10001 /tmp; ' +
          'chmod 1777 /tmp; ' +
          'mkdir -p /var/lib/amazon/ssm /var/log/amazon/ssm; ' +
          'chown -R 10001:10001 /var/lib/amazon /var/log/amazon',
      ],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'volume-init', logGroup }),
    });
    volumeInit.addMountPoints(
      { sourceVolume: 'app-cache', containerPath: '/app/.cache', readOnly: false },
      { sourceVolume: 'tmp', containerPath: '/tmp', readOnly: false },
      { sourceVolume: 'ssm-agent-state', containerPath: '/var/lib/amazon', readOnly: false },
      { sourceVolume: 'ssm-agent-logs', containerPath: '/var/log/amazon', readOnly: false },
    );
    appContainer.addContainerDependencies({
      container: volumeInit,
      condition: ecs.ContainerDependencyCondition.SUCCESS,
    });

    const cfnTaskDefinition = taskDefinition.node.defaultChild as ecs.CfnTaskDefinition;
    for (const index of [0, 1]) {
      cfnTaskDefinition.addPropertyOverride(`ContainerDefinitions.${index}.ReadonlyRootFilesystem`, true);
    }

    // New logical ID: the live stack still has ApplicationLoadBalancedFargateService
    // 'Service'. Updating that resource to CODE_DEPLOY fails CloudFormation
    // validation (controller, load balancers, and ServiceName cannot change).
    this.service = new ecs.FargateService(this, 'BlueGreenService', {
      cluster: this.cluster,
      serviceName: ECS_SERVICE_NAME,
      taskDefinition,
      desiredCount: 2,
      minHealthyPercent: 0,
      maxHealthyPercent: 200,
      assignPublicIp: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      enableExecuteCommand: true,
      healthCheckGracePeriod: cdk.Duration.seconds(120),
      deploymentController: { type: ecs.DeploymentControllerType.CODE_DEPLOY },
    });
    this.service.attachToApplicationTargetGroup(blueTargetGroup);
    this.service.connections.allowFrom(this.loadBalancer, ec2.Port.tcp(APP_CONTAINER_PORT));

    // Pin TaskDefinition to the family name (no revision). CloudFormation `Ref` on a task definition
    // includes the revision, so a later CDK change would try to update Service.TaskDefinition and
    // fail under CODE_DEPLOY. The family string is stable; CodeDeploy owns image rollouts.
    const cfnService = this.service.node.defaultChild as ecs.CfnService;
    cfnService.taskDefinition = TASK_FAMILY;

    const application = new codedeploy.EcsApplication(this, 'CodeDeployApplication', {
      applicationName: CODEDEPLOY_APPLICATION_NAME,
    });
    new codedeploy.EcsDeploymentGroup(this, 'DeploymentGroup', {
      application,
      deploymentGroupName: CODEDEPLOY_DEPLOYMENT_GROUP_NAME,
      service: this.service,
      blueGreenDeploymentConfig: {
        blueTargetGroup,
        greenTargetGroup,
        listener: productionListener,
      },
      autoRollback: { failedDeployment: true },
      deploymentConfig: codedeploy.EcsDeploymentConfig.ALL_AT_ONCE,
    });

    // ---- WAF (internet-facing ALB) ----------------------------------------
    // The two AWS managed rule groups below (CommonRuleSet + KnownBadInputs) inspect the request
    // URI, headers and body and answer any match with a bare 403 that never reaches the application
    // (nothing is logged by the app). Their generic heuristics are fundamentally incompatible with
    // two classes of legitimate traffic this server must accept:
    //
    //   1. OAuth. RFC 8252 native clients (Claude Desktop, MCP Inspector, VS Code, Cursor) register
    //      and authorize with loopback callbacks such as `http://127.0.0.1:8123/callback` - exactly
    //      the redirect URIs the app always allows. Any request carrying a plain `http://` URL was
    //      rejected by the `://` RFI heuristic, so `POST /oauth/register` with a loopback
    //      `redirect_uris` entry, `GET /oauth2/authorize?...&redirect_uri=http%3A%2F%2F127.0.0.1...`
    //      and the matching `POST /oauth2/token` were all blocked while the identical `https://`
    //      requests reached the app. Dynamic Client Registration and the authorization-code flow
    //      were therefore impossible for every local MCP client.
    //
    //   2. The MCP transport itself (`POST /mcp`, plus the legacy `/sse`). MCP is JSON-RPC over HTTP
    //      whose tool arguments and results carry arbitrary user- and model-supplied content: URLs
    //      (`http://...`, the very same `://` heuristic that broke OAuth), snippets of code and
    //      markup that read as XSS/SQLi to the body rules, and payloads that routinely exceed WAF's
    //      8 KB body-inspection limit (`SizeRestrictions_BODY` - an AWS WAF service default on
    //      regional/ALB scope that cannot be raised for an ALB). Left fully covered, the managed
    //      groups would 403 ordinary MCP calls with no app-visible trace - the exact failure mode
    //      already proven on the OAuth endpoints.
    //
    // Both managed rule groups are consequently scoped down so they skip these endpoints, whose
    // legitimate payloads necessarily contain URLs and free-form content. Everything else - the
    // interactive Google login (`/login/**`, `/oauth2/authorization/**`, `/login/oauth2/code/**`),
    // the discovery documents (`/.well-known/**`) and the actuator health probe - stays fully
    // protected. The excluded endpoints are not left unguarded: they keep the rate-based rules below
    // and are strictly protected by the app itself. `/mcp` (and `/sse`) require a valid opaque bearer
    // token on every request (local introspection), enforce the CORS/Origin allowlist (the MCP
    // DNS-rebinding guard) and an SSRF guard on connection targets; the OAuth endpoints enforce exact
    // redirect-URI allowlisting, mandatory PKCE S256 and public-clients-only. A narrower
    // `ruleActionOverrides` on just the offending managed rules would be preferable, but the firing
    // rules could not be identified (the WebACL is not readable with the current IAM permissions and
    // WAF logging was not enabled); the logging configuration added further down makes future blocks
    // diagnosable so this can be tightened to per-rule overrides later.
    const wafManagedRuleExcludedPaths = ['/mcp', '/sse', '/oauth/register', '/oauth2/authorize', '/oauth2/token'];
    const notExcludedEndpoints: wafv2.CfnWebACL.StatementProperty = {
      notStatement: {
        statement: {
          orStatement: {
            statements: wafManagedRuleExcludedPaths.map((uriPathPrefix) => ({
              byteMatchStatement: {
                searchString: uriPathPrefix,
                fieldToMatch: { uriPath: {} },
                positionalConstraint: 'STARTS_WITH',
                textTransformations: [
                  { priority: 0, type: 'URL_DECODE' },
                  { priority: 1, type: 'LOWERCASE' },
                ],
              },
            })),
          },
        },
      },
    };

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
            managedRuleGroupStatement: {
              vendorName: 'AWS',
              name: 'AWSManagedRulesCommonRuleSet',
              scopeDownStatement: notExcludedEndpoints,
            },
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
              scopeDownStatement: notExcludedEndpoints,
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            sampledRequestsEnabled: true,
            metricName: 'KnownBadInputs',
          },
        },
        {
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
      resourceArn: this.loadBalancer.loadBalancerArn,
      webAclArn: webAcl.attrArn,
    });

    const wafLogGroupName = 'aws-waf-logs-broadworks-mcp';
    const wafLogGroup = new logs.LogGroup(this, 'WafLogGroup', {
      logGroupName: wafLogGroupName,
      retention: logs.RetentionDays.ONE_MONTH,
      encryptionKey: dataKey,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const wafLoggingConfiguration = new wafv2.CfnLoggingConfiguration(this, 'WebAclLoggingConfiguration', {
      resourceArn: webAcl.attrArn,
      logDestinationConfigs: [
        this.formatArn({
          service: 'logs',
          resource: 'log-group',
          resourceName: wafLogGroupName,
          arnFormat: cdk.ArnFormat.COLON_RESOURCE_NAME,
        }),
      ],
      redactedFields: [
        { singleHeader: { Name: 'authorization' } },
        { singleHeader: { Name: 'cookie' } },
      ],
    });
    wafLoggingConfiguration.node.addDependency(wafLogGroup);

    // No ALB session stickiness is required. Multi-instance OAuth is durable in DynamoDB.

    if (hostname && hostedZone) {
      const albAliasTarget = route53.RecordTarget.fromAlias(new route53Targets.LoadBalancerTarget(this.loadBalancer));
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

    sessionsTable.grantReadWriteData(taskRole);
    httpSessionsTable.grantReadWriteData(taskRole);
    userConfigTable.grantReadWriteData(taskRole);
    dataKey.grantEncryptDecrypt(taskRole);

    if (pipelineAccount) {
      // OrganizationStack creates these names so Build can assume them before
      // this stack exists. Recreating them here fails with EntityAlreadyExists.
      const ecrPushRole = iam.Role.fromRoleName(this, 'EcrPushRole', ECR_PUSH_ROLE_NAME);
      this.repository.grantPullPush(ecrPushRole);
      ecrPushRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          actions: ['ecr:GetAuthorizationToken'],
          resources: ['*'],
        }),
      );
      ecrPushRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          actions: ['ecs:DescribeTaskDefinition', 'ecs:DescribeServices', 'ecs:DescribeClusters'],
          resources: ['*'],
        }),
      );

      const pipelineDeployRole = iam.Role.fromRoleName(this, 'PipelineDeployRole', PIPELINE_DEPLOY_ROLE_NAME);
      pipelineDeployRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          actions: [
            'codedeploy:CreateDeployment',
            'codedeploy:GetDeployment',
            'codedeploy:GetDeploymentConfig',
            'codedeploy:GetApplication',
            'codedeploy:GetApplicationRevision',
            'codedeploy:RegisterApplicationRevision',
            'codedeploy:GetDeploymentGroup',
          ],
          resources: ['*'],
        }),
      );
      pipelineDeployRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          actions: ['ecs:RegisterTaskDefinition', 'ecs:DescribeTaskDefinition', 'ecs:DescribeServices', 'ecs:DescribeClusters'],
          resources: ['*'],
        }),
      );
      pipelineDeployRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          actions: ['iam:PassRole'],
          resources: [taskRole.roleArn, executionRole.roleArn],
          conditions: {
            StringEquals: { 'iam:PassedToService': 'ecs-tasks.amazonaws.com' },
          },
        }),
      );
      pipelineDeployRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          sid: 'PipelineArtifacts',
          actions: ['s3:GetObject', 's3:GetObjectVersion'],
          resources: ['*'],
          conditions: {
            StringEquals: { 'aws:ResourceAccount': pipelineAccount },
          },
        }),
      );
      pipelineDeployRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          sid: 'PipelineKms',
          actions: ['kms:Decrypt', 'kms:DescribeKey', 'kms:GenerateDataKey'],
          resources: ['*'],
          conditions: {
            StringEquals: { 'aws:ResourceAccount': pipelineAccount },
          },
        }),
      );

      new ssm.StringParameter(this, 'EcrUriParameter', {
        parameterName: '/broadworks-mcp/pipeline/ecr-uri',
        stringValue: this.repository.repositoryUri,
      });
      new ssm.StringParameter(this, 'EcrPushRoleArnParameter', {
        parameterName: '/broadworks-mcp/pipeline/ecr-push-role-arn',
        stringValue: ecrPushRole.roleArn,
      });
      new ssm.StringParameter(this, 'PipelineDeployRoleArnParameter', {
        parameterName: '/broadworks-mcp/pipeline/pipeline-deploy-role-arn',
        stringValue: pipelineDeployRole.roleArn,
      });

      new cdk.CfnOutput(this, 'EcrPushRoleArn', { value: ecrPushRole.roleArn });
      new cdk.CfnOutput(this, 'PipelineDeployRoleArn', { value: pipelineDeployRole.roleArn });
    } else {
      cdk.Annotations.of(this).addWarning(
        'pipelineAccount is not set: EcrPushRole and PipelineDeployRole were not created. Pass ' +
          '-c pipelineAccount=<pipeline-aws-account-id> so MCPCICD can assume into this environment.',
      );
    }

    new cdk.CfnOutput(this, 'LoadBalancerDns', {
      value: this.loadBalancer.loadBalancerDnsName,
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
    new cdk.CfnOutput(this, 'HttpSessionsTableName', { value: httpSessionsTable.tableName });
    new cdk.CfnOutput(this, 'UserConfigTableName', { value: userConfigTable.tableName });
    new cdk.CfnOutput(this, 'KmsKeyId', { value: dataKey.keyId });
    new cdk.CfnOutput(this, 'EcrRepositoryUri', { value: this.repository.repositoryUri });
    new cdk.CfnOutput(this, 'ClusterName', { value: this.cluster.clusterName });
    new cdk.CfnOutput(this, 'ServiceName', { value: this.service.serviceName });
    new cdk.CfnOutput(this, 'CodeDeployApplicationName', { value: CODEDEPLOY_APPLICATION_NAME });
    new cdk.CfnOutput(this, 'CodeDeployDeploymentGroupName', { value: CODEDEPLOY_DEPLOYMENT_GROUP_NAME });

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
