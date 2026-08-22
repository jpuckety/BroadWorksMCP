'use strict';

/**
 * Turn `aws ecs describe-task-definition` JSON into a CodeDeploy taskdef.json:
 * strip registration-only fields and replace only the app container image with
 * `<IMAGE1_NAME>` so the volume-init sidecar is left unchanged.
 *
 * Usage: node prepare-taskdef.js [input.json] > taskdef.json
 */

const APP_CONTAINER_NAME = 'broadworks-mcp';
const IMAGE_PLACEHOLDER = '<IMAGE1_NAME>';

const STRIP_FIELDS = [
  'taskDefinitionArn',
  'revision',
  'status',
  'requiresAttributes',
  'compatibilities',
  'registeredAt',
  'registeredBy',
  'deregisteredAt',
];

function unwrapTaskDefinition(input) {
  if (input && typeof input === 'object' && input.taskDefinition) {
    return input.taskDefinition;
  }
  return input;
}

function stripRegisteredFields(td) {
  const clone = JSON.parse(JSON.stringify(td));
  for (const field of STRIP_FIELDS) {
    delete clone[field];
  }
  return clone;
}

function substituteAppImage(td, placeholder, containerName) {
  const name = containerName || APP_CONTAINER_NAME;
  const ph = placeholder || IMAGE_PLACEHOLDER;
  if (!Array.isArray(td.containerDefinitions)) {
    throw new Error('task definition missing containerDefinitions');
  }
  let found = false;
  for (const container of td.containerDefinitions) {
    if (container.name === name) {
      container.image = ph;
      found = true;
    }
  }
  if (!found) {
    throw new Error(`container '${name}' not found in task definition`);
  }
  return td;
}

function prepareTaskdef(input, options) {
  const opts = options || {};
  const td = stripRegisteredFields(unwrapTaskDefinition(input));
  return substituteAppImage(td, opts.placeholder, opts.containerName);
}

function appspecYaml(containerName, containerPort) {
  const name = containerName || APP_CONTAINER_NAME;
  const port = containerPort || 8080;
  return [
    'version: 0.0',
    'Resources:',
    '  - TargetService:',
    '      Type: AWS::ECS::Service',
    '      Properties:',
    '        TaskDefinition: <TASK_DEFINITION>',
    '        LoadBalancerInfo:',
    `          ContainerName: "${name}"`,
    `          ContainerPort: ${port}`,
    '',
  ].join('\n');
}

module.exports = {
  APP_CONTAINER_NAME,
  IMAGE_PLACEHOLDER,
  unwrapTaskDefinition,
  stripRegisteredFields,
  substituteAppImage,
  prepareTaskdef,
  appspecYaml,
};

if (require.main === module) {
  const fs = require('fs');
  const path = process.argv[2];
  const raw = path ? fs.readFileSync(path, 'utf8') : fs.readFileSync(0, 'utf8');
  process.stdout.write(JSON.stringify(prepareTaskdef(JSON.parse(raw)), null, 2) + '\n');
}
