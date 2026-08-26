import * as fs from 'fs';
import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import {
  APP_CONTAINER_NAME,
  APP_CONTAINER_PORT,
  APP_HEALTH_CHECK_PATH,
  BroadWorksMcpStack,
  CODEDEPLOY_APPLICATION_NAME,
  ECR_REPOSITORY_NAME,
  ECR_PUSH_ROLE_NAME,
  PIPELINE_DEPLOY_ROLE_NAME,
  PLACEHOLDER_IMAGE,
  TASK_FAMILY,
} from '../lib/broadworks-mcp-stack';

const PIPELINE_ACCOUNT = '111111111111';
const ENV_ACCOUNT = '222222222222';
const REGION = 'us-east-1';

function synth(props: Partial<ConstructorParameters<typeof BroadWorksMcpStack>[2]> = {}): Template {
  const app = new cdk.App();
  const stack = new BroadWorksMcpStack(app, 'Test', {
    env: { account: ENV_ACCOUNT, region: REGION },
    allowInsecureHttp: true,
    pipelineAccount: PIPELINE_ACCOUNT,
    ...props,
  });
  return Template.fromStack(stack);
}

describe('BroadWorksMcpStack', () => {
  test('replaces the rolling Service construct with BlueGreenService', () => {
    const src = fs.readFileSync(path.join(__dirname, '../lib/broadworks-mcp-stack.ts'), 'utf8');
    expect(src).toContain("new ecs.FargateService(this, 'BlueGreenService'");
    expect(src).not.toContain('new ecsPatterns.ApplicationLoadBalancedFargateService');
  });

  test('uses CODE_DEPLOY, two target groups, ECR, and no DockerImageAsset', () => {
    const template = synth();

    template.hasResourceProperties('AWS::ECS::Service', {
      DeploymentController: { Type: 'CODE_DEPLOY' },
      DesiredCount: 1,
      LaunchType: 'FARGATE',
      NetworkConfiguration: {
        AwsvpcConfiguration: {
          AssignPublicIp: 'DISABLED',
        },
      },
    });
    template.hasResourceProperties('AWS::ECS::Service', {
      TaskDefinition: TASK_FAMILY,
    });

    template.resourceCountIs('AWS::ElasticLoadBalancingV2::TargetGroup', 2);
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
      HealthCheckPath: APP_HEALTH_CHECK_PATH,
      Port: APP_CONTAINER_PORT,
      TargetType: 'ip',
    });

    const json = JSON.stringify(template.toJSON());
    expect(json).toContain(ECR_REPOSITORY_NAME);
    expect(json).not.toMatch(/"AWS::ECR::Repository"/);

    template.hasResourceProperties('AWS::CodeDeploy::Application', {
      ApplicationName: CODEDEPLOY_APPLICATION_NAME,
      ComputePlatform: 'ECS',
    });
    template.resourceCountIs('AWS::CodeDeploy::DeploymentGroup', 1);

    expect(json).not.toMatch(/DockerImageAsset/);
    expect(json).not.toMatch(/aws:cdk:path:.+ImageAsset/);
    expect(json).toContain(PLACEHOLDER_IMAGE);
    expect(json).toContain(APP_CONTAINER_NAME);
  });

  test('retains DynamoDB, KMS, and WAF association on the ALB', () => {
    const template = synth();

    template.resourceCountIs('AWS::DynamoDB::Table', 3);
    template.hasResourceProperties('AWS::KMS::Alias', { AliasName: 'alias/broadworks-mcp' });
    template.hasResourceProperties('AWS::WAFv2::WebACL', { Name: 'broadworks-mcp-web-acl', Scope: 'REGIONAL' });
    template.resourceCountIs('AWS::WAFv2::WebACLAssociation', 1);
  });

  test('task definition uses family, app container, sidecar, and read-only root', () => {
    const template = synth();

    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      Family: TASK_FAMILY,
      ContainerDefinitions: Match.arrayWith([
        Match.objectLike({
          Name: APP_CONTAINER_NAME,
          PortMappings: Match.arrayWith([Match.objectLike({ ContainerPort: APP_CONTAINER_PORT })]),
          ReadonlyRootFilesystem: true,
        }),
        Match.objectLike({
          Name: 'volume-init',
          Essential: false,
          ReadonlyRootFilesystem: true,
        }),
      ]),
    });
  });

  test('placeholder app container stays running and answers the ALB health check', () => {
    const template = synth();

    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: Match.arrayWith([
        Match.objectLike({
          Name: APP_CONTAINER_NAME,
          Essential: true,
          Image: PLACEHOLDER_IMAGE,
          EntryPoint: ['sh', '-c'],
          Command: Match.arrayWith([
            Match.stringLikeRegexp('httpd'),
          ]),
        }),
      ]),
    });

    const taskDefs = Object.values(template.findResources('AWS::ECS::TaskDefinition'));
    const app = taskDefs[0].Properties.ContainerDefinitions.find(
      (c: { Name: string }) => c.Name === APP_CONTAINER_NAME,
    );
    expect(app.Command.join(' ')).toMatch(/8080/);
    expect(app.Command.join(' ')).toMatch(/actuator\/health/);
  });

  test('real imageUri does not keep the placeholder listen command', () => {
    const template = synth({
      imageUri: '222222222222.dkr.ecr.us-east-1.amazonaws.com/broadworks-mcp@sha256:abc',
    });
    const taskDefs = Object.values(template.findResources('AWS::ECS::TaskDefinition'));
    const app = taskDefs[0].Properties.ContainerDefinitions.find(
      (c: { Name: string }) => c.Name === APP_CONTAINER_NAME,
    );
    expect(app.Command).toBeUndefined();
    expect(app.EntryPoint).toBeUndefined();
  });

  test('merges pipeline containerEnv onto the app container', () => {
    const app = new cdk.App({
      context: {
        containerEnv: {
          OAUTH_REDIRECT_ALLOWLIST: 'https://www.cursor.com/agents/mcp/oauth/callback',
          OAUTH_ALLOW_KNOWN_CLIENTS: 'true',
        },
      },
    });
    const template = Template.fromStack(
      new BroadWorksMcpStack(app, 'Test', {
        env: { account: ENV_ACCOUNT, region: REGION },
        allowInsecureHttp: true,
        pipelineAccount: PIPELINE_ACCOUNT,
      }),
    );
    const taskDefs = Object.values(template.findResources('AWS::ECS::TaskDefinition'));
    const container = taskDefs[0].Properties.ContainerDefinitions.find(
      (c: { Name: string }) => c.Name === APP_CONTAINER_NAME,
    );
    const env = Object.fromEntries(
      (container.Environment as Array<{ Name: string; Value: string }>).map((entry) => [entry.Name, entry.Value]),
    );
    expect(env.OAUTH_REDIRECT_ALLOWLIST).toBe('https://www.cursor.com/agents/mcp/oauth/callback');
    expect(env.OAUTH_ALLOW_KNOWN_CLIENTS).toBe('true');
  });

  test('pipeline roles are imported and granted by name', () => {
    const template = synth();
    const roles = Object.values(template.findResources('AWS::IAM::Role'));
    expect(roles.some((role) => role.Properties?.RoleName === ECR_PUSH_ROLE_NAME)).toBe(false);
    expect(roles.some((role) => role.Properties?.RoleName === PIPELINE_DEPLOY_ROLE_NAME)).toBe(false);
    const json = JSON.stringify(template.toJSON());
    expect(json).toContain(ECR_PUSH_ROLE_NAME);
    expect(json).toContain(PIPELINE_DEPLOY_ROLE_NAME);
    expect(json).toContain('ecs:DescribeTaskDefinition');
    expect(json).toContain(PIPELINE_ACCOUNT);
  });

  test('container name, port, and health path match the Dockerfile contract', () => {
    expect(APP_CONTAINER_NAME).toBe('broadworks-mcp');
    expect(APP_CONTAINER_PORT).toBe(8080);
    expect(APP_HEALTH_CHECK_PATH).toBe('/actuator/health');
  });

  test('Dockerfile pulls official bases from ECR Public, not Docker Hub', () => {
    const dockerfile = fs.readFileSync(path.join(__dirname, '../../Dockerfile'), 'utf8');
    expect(dockerfile).toMatch(/^FROM public\.ecr\.aws\/docker\/library\/maven:/m);
    expect(dockerfile).toMatch(/^FROM public\.ecr\.aws\/docker\/library\/eclipse-temurin:/m);
    expect(dockerfile).not.toMatch(/^FROM maven:/m);
    expect(dockerfile).not.toMatch(/^FROM eclipse-temurin:/m);
  });

  test('PR workflow runs Maven verify and docker build', () => {
    const yml = fs.readFileSync(path.join(__dirname, '../../.github/workflows/pr-ci.yml'), 'utf8');
    expect(yml).toContain('mvn -B -Pinstall-alpaca verify');
    expect(yml).toContain('docker build');
  });
});
