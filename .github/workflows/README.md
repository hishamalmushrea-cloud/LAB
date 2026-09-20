# Imported workflow safety guard

The workflows in this directory came from
[`appdevforall/CodeOnTheGo`](https://github.com/appdevforall/CodeOnTheGo).
They reference App Dev for All self-hosted runners, signing material, deployment
hosts, Firebase, Jira, Slack, Crowdin, Cloudflare, and SonarCloud.

To prevent a transferred repository or fork from accidentally queueing those
jobs or attempting upstream deployments, every job is guarded by:

```yaml
if: >-
  (github.repository == 'appdevforall/CodeOnTheGo' ||
   vars.COTG_ENABLE_UPSTREAM_WORKFLOWS == 'true')
```

This leaves upstream behavior unchanged and makes the imported automation
opt-in everywhere else.

## Enabling workflows in another repository

Only enable the guard after adapting and reviewing the workflows:

1. Provision appropriately isolated runners or replace `self-hosted` labels.
2. Create repository-specific signing and deployment secrets; never copy
   upstream credentials.
3. Replace App Dev for All hosts, project IDs, Jira/Crowdin/Cloudflare settings,
   and distribution targets.
4. Reduce each workflow's token permissions and pin third-party actions to
   immutable commit SHAs.
5. Separate build/test checks from jobs that publish, merge, sign, or deploy.
6. Create the repository Actions variable
   `COTG_ENABLE_UPSTREAM_WORKFLOWS=true`.

Leaving the variable unset (the default) makes all imported jobs skip outside
the upstream repository.
