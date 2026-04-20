// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div>
    <a-tabs v-model:activeKey="activeTab">
      <a-tab-pane key="sts" :tab="$t('label.aws.s3.connection.sts.title')" v-if="resource.stsendpoint">
        <a-card>
          <p>{{ $t('label.aws.s3.connection.sts.description') }}</p>

          <h4>~/.aws/credentials</h4>
          <a-typography-paragraph :copyable="{ text: stsCredentialsConfig }">
            <pre class="config-block">{{ stsCredentialsConfig }}</pre>
          </a-typography-paragraph>

          <h4>~/.aws/config</h4>
          <a-typography-paragraph :copyable="{ text: stsProfileConfig }">
            <pre class="config-block">{{ stsProfileConfig }}</pre>
          </a-typography-paragraph>

          <h4>{{ $t('label.usage') }}</h4>
          <a-typography-paragraph :copyable="{ text: stsUsageExample }">
            <pre class="config-block">{{ stsUsageExample }}</pre>
          </a-typography-paragraph>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="s3proxy" :tab="$t('label.aws.s3.connection.s3proxy.title')" v-if="resource.s3endpoint">
        <a-card>
          <p>{{ $t('label.aws.s3.connection.s3proxy.description') }}</p>

          <h4>{{ $t('label.usage') }}</h4>
          <a-typography-paragraph :copyable="{ text: s3ProxyExample }">
            <pre class="config-block">{{ s3ProxyExample }}</pre>
          </a-typography-paragraph>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="details" :tab="$t('label.details')">
        <a-card>
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item :label="$t('label.access.key')">
              <a-typography-paragraph :copyable="{ text: resource.accesskey }" style="margin-bottom: 0">
                {{ resource.accesskey }}
              </a-typography-paragraph>
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.secret.key')">
              <a-typography-paragraph :copyable="{ text: resource.usersecretkey }" style="margin-bottom: 0">
                <span v-if="!showSecret">••••••••••••</span>
                <span v-else>{{ resource.usersecretkey }}</span>
                <a-button type="link" size="small" @click="showSecret = !showSecret">
                  {{ showSecret ? $t('label.hide') : $t('label.show') }}
                </a-button>
              </a-typography-paragraph>
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.aws.s3.region')" v-if="resource.s3region">
              {{ resource.s3region }}
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.aws.s3.sts.endpoint')" v-if="resource.stsendpoint">
              {{ resource.stsendpoint }}
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.aws.s3.s3.endpoint')" v-if="resource.s3endpoint">
              {{ resource.s3endpoint }}
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.aws.s3.role.arn')" v-if="resource.rolearn">
              {{ resource.rolearn }}
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.aws.s3.connection.public.url')">
              <a-typography-paragraph :copyable="{ text: resource.url }" style="margin-bottom: 0">
                {{ resource.url }}
              </a-typography-paragraph>
            </a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script>
export default {
  name: 'AWSS3BucketConnection',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      activeTab: this.resource.stsendpoint ? 'sts' : (this.resource.s3endpoint ? 's3proxy' : 'details'),
      showSecret: false
    }
  },
  computed: {
    stsCredentialsConfig () {
      return `[cloudstack-source]
aws_access_key_id = ${this.resource.accesskey}
aws_secret_access_key = ${this.resource.usersecretkey}`
    },
    stsProfileConfig () {
      return `[profile cloudstack-s3]
role_arn = ${this.resource.rolearn || '<role-arn>'}
source_profile = cloudstack-source
region = ${this.resource.s3region || 'us-east-1'}
services = cloudstack-svc

[services cloudstack-svc]
sts =
  endpoint_url = ${this.resource.stsendpoint}`
    },
    stsUsageExample () {
      return `# List objects in bucket
aws s3 ls s3://${this.resource.name} --profile cloudstack-s3

# Upload a file
aws s3 cp myfile.txt s3://${this.resource.name}/ --profile cloudstack-s3

# Download a file
aws s3 cp s3://${this.resource.name}/myfile.txt . --profile cloudstack-s3`
    },
    s3ProxyExample () {
      return `export AWS_ACCESS_KEY_ID=${this.resource.accesskey}
export AWS_SECRET_ACCESS_KEY=${this.resource.usersecretkey}

# List objects in bucket
aws s3 ls s3://${this.resource.name} --endpoint-url ${this.resource.s3endpoint}

# Upload a file
aws s3 cp myfile.txt s3://${this.resource.name}/ --endpoint-url ${this.resource.s3endpoint}

# Download a file
aws s3 cp s3://${this.resource.name}/myfile.txt . --endpoint-url ${this.resource.s3endpoint}`
    }
  }
}
</script>

<style scoped>
.config-block {
  background-color: #f5f5f5;
  padding: 12px;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  line-height: 1.5;
  overflow-x: auto;
  white-space: pre;
  margin: 0;
}
h4 {
  margin-top: 16px;
  margin-bottom: 8px;
}
</style>
