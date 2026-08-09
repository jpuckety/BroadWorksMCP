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

export interface BroadWorksMcpStackProps extends cdk.StackProps {
  /** ARN of the ACM certificate for the HTTPS ALB listener. */
  readonly certificateArn?: string;
}

/**
 * Deploys broadworks-mcp on ECS Fargate behind an (HTTPS) Application Load Balancer, with two
 * DynamoDB tables encrypted by a customer-managed KMS key, a task IAM role granting scoped
 * KMS + DynamoDB access (the blueprint's "IRSA" role), and SSM SecureString-backed secrets.
 */
export class BroadWorksMcpStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: BroadWorksMcpStackProps = {}) {
    super(scope, id, props);

    const applicationId = this.node.tryGetContext('applicationId') ?? 'broadworks-mcp';
    const oauthRedirectAllowlist = this.node.tryGetContext('oauthRedirectAllowlist') ?? '';
    const ssmNames = this.node.tryGetContext('ssm') ?? {};

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
    const publicBaseUrl = ssm.StringParameter.fromSecureStringParameterAttributes(this, 'PublicBaseUrl', {
      parameterName: ssmNames.publicBaseUrl ?? '/broadworks-mcp/public-base-url',
    });

    // ---- Container image (built from the repo root Dockerfile) ------------
    const image = ecs.ContainerImage.fromAsset(path.join(__dirname, '..', '..'), {
      file: 'Dockerfile',
    });

    const logGroup = new logs.LogGroup(this, 'LogGroup', {
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, containerInsights: true });

    const certificate = props.certificateArn
      ? acm.Certificate.fromCertificateArn(this, 'Certificate', props.certificateArn)
      : undefined;

    // ---- Fargate service behind an ALB ------------------------------------
    const service = new ecsPatterns.ApplicationLoadBalancedFargateService(this, 'Service', {
      cluster,
      cpu: 512,
      memoryLimitMiB: 1024,
      desiredCount: 2,
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
        },
        secrets: {
          GOOGLE_CLIENT_ID: ecs.Secret.fromSsmParameter(googleClientId),
          GOOGLE_CLIENT_SECRET: ecs.Secret.fromSsmParameter(googleClientSecret),
          PUBLIC_BASE_URL: ecs.Secret.fromSsmParameter(publicBaseUrl),
        },
      },
    });

    // Actuator health probe for ALB target group.
    service.targetGroup.configureHealthCheck({
      path: '/actuator/health',
      healthyHttpCodes: '200',
      interval: cdk.Duration.seconds(30),
    });

    // ---- Grants (least privilege) -----------------------------------------
    sessionsTable.grantReadWriteData(taskRole);
    userConfigTable.grantReadWriteData(taskRole);
    dataKey.grantEncryptDecrypt(taskRole);

    // ---- Outputs ----------------------------------------------------------
    new cdk.CfnOutput(this, 'LoadBalancerDns', {
      value: service.loadBalancer.loadBalancerDnsName,
      description: 'Public DNS name of the MCP load balancer',
    });
    new cdk.CfnOutput(this, 'SessionsTableName', { value: sessionsTable.tableName });
    new cdk.CfnOutput(this, 'UserConfigTableName', { value: userConfigTable.tableName });
    new cdk.CfnOutput(this, 'KmsKeyId', { value: dataKey.keyId });

    if (!certificate) {
      cdk.Annotations.of(this).addWarning(
        'No ACM certificate provided: the ALB listens on HTTP only. Provide -c certificateArn=... for HTTPS in production.',
      );
    }
  }
}
