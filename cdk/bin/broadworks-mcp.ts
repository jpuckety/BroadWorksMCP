#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { BroadWorksMcpStack } from '../lib/broadworks-mcp-stack';

const app = new cdk.App();

new BroadWorksMcpStack(app, 'BroadWorksMcpStack', {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION,
  },
  // ACM certificate for the HTTPS ALB listener. Provide via:
  //   cdk deploy -c certificateArn=arn:aws:acm:...:certificate/...
  // or the CERTIFICATE_ARN environment variable. If absent, an HTTP-only
  // listener is created (development only).
  certificateArn: app.node.tryGetContext('certificateArn') ?? process.env.CERTIFICATE_ARN,
});
