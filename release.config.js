let config = require('semantic-release-preconfigured-conventional-commits');
config.tagFormat = 'nanopub-query-${version}'
config.branches = ['release']
config.plugins.push(
  [
    "@terrestris/maven-semantic-release",
    {
      "mavenTarget": "package jib:build",
      "settingsPath": "./settings.xml",
      "updateSnapshotVersion": true,
      "mvnw": true
    }
  ],
  [
    "@semantic-release/github",
    {
      // Don't comment on referenced issues after a release.
      //
      // The success step resolves every issue reference in the released commits
      // and comments on it — but it looks each number up in THIS repo, dropping
      // any owner/repo qualifier. Commit messages here routinely cite upstream
      // rdf4j issues, so `eclipse-rdf4j/rdf4j#4775` became a lookup for
      // knowledgepixels/nanopub-query#4775, which does not exist:
      //
      //   Failed step "success" of plugin "@semantic-release/github"
      //   Error: Could not resolve to an issue or pull request with the number of 4775.
      //
      // The release itself had already completed at that point (tag, GitHub
      // release, and the pushed image), so the only casualty was the workflow's
      // final "Update main branch after release" step, which never ran and left
      // main without the release commit (1.27.1, 2026-08-24). Releases must not
      // hinge on a courtesy comment resolving.
      //
      // Failure reporting is deliberately left on: it opens an issue in this
      // repo and cannot hit the same lookup.
      successCommentCondition: false
    }
  ],
  "@semantic-release/git"
)
module.exports = config