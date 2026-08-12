#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { BroadWorksMcpStack } from '../lib/broadworks-mcp-stack';

const app = new cdk.App();

new BroadWorksMcpStack(app, 'BroadWorksMcpStack', {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION,
  },
  // Public hostname used to build the server base URL (https://<hostname>) and to create the
  // ACM certificate for the HTTPS ALB listener. Provide via:
  //   cdk deploy -c hostname=mcp.example.com
  // or the PUBLIC_HOSTNAME environment variable.
  hostname: app.node.tryGetContext('hostname') ?? process.env.PUBLIC_HOSTNAME,
  // Optional: reuse an existing, already-validated ACM certificate instead of creating one from
  // the hostname. Provide via:
  //   cdk deploy -c certificateArn=arn:aws:acm:...:certificate/...
  // or the CERTIFICATE_ARN environment variable. If neither hostname nor certificate is given,
  // synthesis fails unless the insecure opt-out below is set.
  certificateArn: app.node.tryGetContext('certificateArn') ?? process.env.CERTIFICATE_ARN,
  // Local/dev only: allow a plain HTTP listener when no hostname/certificate is available.
  //   cdk synth -c allowInsecureHttp=true
  // or ALLOW_INSECURE_HTTP=true.
  allowInsecureHttp:
    String(app.node.tryGetContext('allowInsecureHttp') ?? process.env.ALLOW_INSECURE_HTTP ?? 'false') === 'true',
  // Route 53 hosted zone that owns the hostname. When provided, the stack creates the DNS alias
  // records (hostname -> ALB) and DNS-validates the certificate automatically. When omitted, the
  // zone name is derived from the hostname (mcp.example.com -> example.com). Provide via:
  //   cdk deploy -c hostedZoneName=example.com [-c hostedZoneId=Z123...]
  // or the HOSTED_ZONE_NAME / HOSTED_ZONE_ID environment variables.
  hostedZoneName: app.node.tryGetContext('hostedZoneName') ?? process.env.HOSTED_ZONE_NAME,
  hostedZoneId: app.node.tryGetContext('hostedZoneId') ?? process.env.HOSTED_ZONE_ID,
});
