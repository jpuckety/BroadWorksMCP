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
  // an HTTP-only listener is created (development only).
  certificateArn: app.node.tryGetContext('certificateArn') ?? process.env.CERTIFICATE_ARN,
});
