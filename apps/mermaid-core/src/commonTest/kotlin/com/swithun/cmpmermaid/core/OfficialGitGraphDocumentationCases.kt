package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/gitgraph.md.
 * Upstream document SHA-256: 6afef2eaa11254f051f826fc3a422507d21c8e7ea84c38b27054381566d81cec
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:gitgraph-doc-fixtures
 */
internal data class MermaidGitGraphDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialGitGraphDocumentationCases: List<MermaidGitGraphDocCase> = listOf(
    MermaidGitGraphDocCase(
        id = "001_gitgraph_diagrams",
        title = "GitGraph Diagrams",
        source = """
---
title: Example Git diagram
---
gitGraph
   commit
   commit
   branch develop
   checkout develop
   commit
   commit
   checkout main
   merge develop
   commit
   commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "002_syntax",
        title = "Syntax",
        source = """
gitGraph
       commit
       commit
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "003_adding_custom_commit_id",
        title = "Adding custom commit id",
        source = """
gitGraph
       commit id: "Alpha"
       commit id: "Beta"
       commit id: "Gamma"
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "004_modifying_commit_type",
        title = "Modifying commit type",
        source = """
gitGraph
       commit id: "Normal"
       commit
       commit id: "Reverse" type: REVERSE
       commit
       commit id: "Highlight" type: HIGHLIGHT
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "005_adding_tags",
        title = "Adding Tags",
        source = """
gitGraph
       commit
       commit id: "Normal" tag: "v1.0.0"
       commit
       commit id: "Reverse" type: REVERSE tag: "RC_1"
       commit
       commit id: "Highlight" type: HIGHLIGHT tag: "8.8.4"
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "006_create_a_new_branch",
        title = "Create a new branch",
        source = """
gitGraph
       commit
       commit
       branch develop
       commit
       commit
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "007_checking_out_an_existing_branch",
        title = "Checking out an existing branch",
        source = """
gitGraph
       commit
       commit
       branch develop
       commit
       commit
       commit
       checkout main
       commit
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "008_merging_two_branches",
        title = "Merging two branches",
        source = """
gitGraph
       commit
       commit
       branch develop
       commit
       commit
       commit
       checkout main
       commit
       commit
       merge develop
       commit
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "009_merging_two_branches",
        title = "Merging two branches",
        source = """
gitGraph
       commit id: "1"
       commit id: "2"
       branch nice_feature
       checkout nice_feature
       commit id: "3"
       checkout main
       commit id: "4"
       checkout nice_feature
       branch very_nice_feature
       checkout very_nice_feature
       commit id: "5"
       checkout main
       commit id: "6"
       checkout nice_feature
       commit id: "7"
       checkout main
       merge nice_feature id: "customID" tag: "customTag" type: REVERSE
       checkout very_nice_feature
       commit id: "8"
       checkout main
       commit id: "9"
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "010_cherry_pick_commit_from_another_branch",
        title = "Cherry Pick commit from another branch",
        source = """
gitGraph
        commit id: "ZERO"
        branch develop
        branch release
        commit id:"A"
        checkout main
        commit id:"ONE"
        checkout develop
        commit id:"B"
        checkout main
        merge develop id:"MERGE"
        commit id:"TWO"
        checkout release
        cherry-pick id:"MERGE" parent:"B"
        commit id:"THREE"
        checkout develop
        commit id:"C"
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "011_hiding_branch_names_and_lines",
        title = "Hiding Branch names and lines",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  gitGraph:
    showBranches: false
---
      gitGraph
        commit
        branch hotfix
        checkout hotfix
        commit
        branch develop
        checkout develop
        commit id:"ash" tag:"abc"
        branch featureB
        checkout featureB
        commit type:HIGHLIGHT
        checkout main
        checkout hotfix
        commit type:NORMAL
        checkout develop
        commit type:REVERSE
        checkout featureB
        commit
        checkout main
        merge hotfix
        checkout featureB
        commit
        checkout develop
        branch featureA
        commit
        checkout develop
        merge hotfix
        checkout featureA
        commit
        checkout featureB
        commit
        checkout develop
        merge featureA
        branch release
        checkout release
        commit
        checkout main
        commit
        checkout release
        merge main
        checkout develop
        merge release
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "012_commit_labels_layout_rotated_or_horizontal",
        title = "Commit labels Layout: Rotated or Horizontal",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  gitGraph:
    rotateCommitLabel: true
---
gitGraph
  commit id: "feat(api): ..."
  commit id: "a"
  commit id: "b"
  commit id: "fix(client): .extra long label.."
  branch c2
  commit id: "feat(modules): ..."
  commit id: "test(client): ..."
  checkout main
  commit id: "fix(api): ..."
  commit id: "ci: ..."
  branch b1
  commit
  branch b2
  commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "013_commit_labels_layout_rotated_or_horizontal",
        title = "Commit labels Layout: Rotated or Horizontal",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  gitGraph:
    rotateCommitLabel: false
---
gitGraph
  commit id: "feat(api): ..."
  commit id: "a"
  commit id: "b"
  commit id: "fix(client): .extra long label.."
  branch c2
  commit id: "feat(modules): ..."
  commit id: "test(client): ..."
  checkout main
  commit id: "fix(api): ..."
  commit id: "ci: ..."
  branch b1
  commit
  branch b2
  commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "014_hiding_commit_labels",
        title = "Hiding commit labels",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  gitGraph:
    showBranches: false
    showCommitLabel: false
---
      gitGraph
        commit
        branch hotfix
        checkout hotfix
        commit
        branch develop
        checkout develop
        commit id:"ash"
        branch featureB
        checkout featureB
        commit type:HIGHLIGHT
        checkout main
        checkout hotfix
        commit type:NORMAL
        checkout develop
        commit type:REVERSE
        checkout featureB
        commit
        checkout main
        merge hotfix
        checkout featureB
        commit
        checkout develop
        branch featureA
        commit
        checkout develop
        merge hotfix
        checkout featureA
        commit
        checkout featureB
        commit
        checkout develop
        merge featureA
        branch release
        checkout release
        commit
        checkout main
        commit
        checkout release
        merge main
        checkout develop
        merge release
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "015_customizing_main_branch_name",
        title = "Customizing main branch name",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  gitGraph:
    showBranches: true
    showCommitLabel: true
    mainBranchName: 'MetroLine1'
---
      gitGraph
        commit id:"NewYork"
        commit id:"Dallas"
        branch MetroLine2
        commit id:"LosAngeles"
        commit id:"Chicago"
        commit id:"Houston"
        branch MetroLine3
        commit id:"Phoenix"
        commit type: HIGHLIGHT id:"Denver"
        commit id:"Boston"
        checkout MetroLine1
        commit id:"Atlanta"
        merge MetroLine3
        commit id:"Miami"
        commit id:"Washington"
        merge MetroLine2 tag:"MY JUNCTION"
        commit id:"Boston"
        commit id:"Detroit"
        commit type:REVERSE id:"SanFrancisco"
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "016_customizing_branch_ordering",
        title = "Customizing branch ordering",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  gitGraph:
    showBranches: true
    showCommitLabel: true
---
      gitGraph
      commit
      branch test1 order: 3
      branch test2 order: 2
      branch test3 order: 1
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "017_customizing_branch_ordering",
        title = "Customizing branch ordering",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  gitGraph:
    showBranches: true
    showCommitLabel: true
    mainBranchOrder: 2
---
      gitGraph
      commit
      branch test1 order: 3
      branch test2
      branch test3
      branch test4 order: 1
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "018_left_to_right_default_lr",
        title = "Left to Right (default, `LR:`)",
        source = """
gitGraph LR:
       commit
       commit
       branch develop
       commit
       commit
       checkout main
       commit
       commit
       merge develop
       commit
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "019_top_to_bottom_tb",
        title = "Top to Bottom (`TB:`)",
        source = """
gitGraph TB:
       commit
       commit
       branch develop
       commit
       commit
       checkout main
       commit
       commit
       merge develop
       commit
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "020_bottom_to_top_bt_v11_0_0",
        title = "Bottom to Top (`BT:`) (v11.0.0+)",
        source = """
gitGraph BT:
       commit
       commit
       branch develop
       commit
       commit
       checkout main
       commit
       commit
       merge develop
       commit
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "021_temporal_commits_default_parallelcommits_false",
        title = "Temporal Commits (default, `parallelCommits: false`)",
        source = """
---
config:
  gitGraph:
    parallelCommits: false
---
gitGraph:
  commit
  branch develop
  commit
  commit
  checkout main
  commit
  commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "022_parallel_commits_parallelcommits_true",
        title = "Parallel commits (`parallelCommits: true`)",
        source = """
---
config:
  gitGraph:
    parallelCommits: true
---
gitGraph:
  commit
  branch develop
  commit
  commit
  checkout main
  commit
  commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "023_base_theme",
        title = "Base Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
---
      gitGraph
        commit
        branch hotfix
        checkout hotfix
        commit
        branch develop
        checkout develop
        commit id:"ash" tag:"abc"
        branch featureB
        checkout featureB
        commit type:HIGHLIGHT
        checkout main
        checkout hotfix
        commit type:NORMAL
        checkout develop
        commit type:REVERSE
        checkout featureB
        commit
        checkout main
        merge hotfix
        checkout featureB
        commit
        checkout develop
        branch featureA
        commit
        checkout develop
        merge hotfix
        checkout featureA
        commit
        checkout featureB
        commit
        checkout develop
        merge featureA
        branch release
        checkout release
        commit
        checkout main
        commit
        checkout release
        merge main
        checkout develop
        merge release
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "024_forest_theme",
        title = "Forest Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'forest'
---
      gitGraph
        commit
        branch hotfix
        checkout hotfix
        commit
        branch develop
        checkout develop
        commit id:"ash" tag:"abc"
        branch featureB
        checkout featureB
        commit type:HIGHLIGHT
        checkout main
        checkout hotfix
        commit type:NORMAL
        checkout develop
        commit type:REVERSE
        checkout featureB
        commit
        checkout main
        merge hotfix
        checkout featureB
        commit
        checkout develop
        branch featureA
        commit
        checkout develop
        merge hotfix
        checkout featureA
        commit
        checkout featureB
        commit
        checkout develop
        merge featureA
        branch release
        checkout release
        commit
        checkout main
        commit
        checkout release
        merge main
        checkout develop
        merge release
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "025_the_default_theme",
        title = "The `default` Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
---
      gitGraph
        commit type:HIGHLIGHT
        branch hotfix
        checkout hotfix
        commit
        branch develop
        checkout develop
        commit id:"ash" tag:"abc"
        branch featureB
        checkout featureB
        commit type:HIGHLIGHT
        checkout main
        checkout hotfix
        commit type:NORMAL
        checkout develop
        commit type:REVERSE
        checkout featureB
        commit
        checkout main
        merge hotfix
        checkout featureB
        commit
        checkout develop
        branch featureA
        commit
        checkout develop
        merge hotfix
        checkout featureA
        commit
        checkout featureB
        commit
        checkout develop
        merge featureA
        branch release
        checkout release
        commit
        checkout main
        commit
        checkout release
        merge main
        checkout develop
        merge release
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "026_dark_theme",
        title = "Dark Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'dark'
---
      gitGraph
        commit
        branch hotfix
        checkout hotfix
        commit
        branch develop
        checkout develop
        commit id:"ash" tag:"abc"
        branch featureB
        checkout featureB
        commit type:HIGHLIGHT
        checkout main
        checkout hotfix
        commit type:NORMAL
        checkout develop
        commit type:REVERSE
        checkout featureB
        commit
        checkout main
        merge hotfix
        checkout featureB
        commit
        checkout develop
        branch featureA
        commit
        checkout develop
        merge hotfix
        checkout featureA
        commit
        checkout featureB
        commit
        checkout develop
        merge featureA
        branch release
        checkout release
        commit
        checkout main
        commit
        checkout release
        merge main
        checkout develop
        merge release
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "027_neutral_theme",
        title = "Neutral Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'neutral'
---
      gitGraph
        commit
        branch hotfix
        checkout hotfix
        commit
        branch develop
        checkout develop
        commit id:"ash" tag:"abc"
        branch featureB
        checkout featureB
        commit type:HIGHLIGHT
        checkout main
        checkout hotfix
        commit type:NORMAL
        checkout develop
        commit type:REVERSE
        checkout featureB
        commit
        checkout main
        merge hotfix
        checkout featureB
        commit
        checkout develop
        branch featureA
        commit
        checkout develop
        merge hotfix
        checkout featureA
        commit
        checkout featureB
        commit
        checkout develop
        merge featureA
        branch release
        checkout release
        commit
        checkout main
        commit
        checkout release
        merge main
        checkout develop
        merge release
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "028_customize_using_theme_variables",
        title = "Customize using Theme Variables",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
---
       gitGraph
       commit
       branch develop
       commit tag:"v1.0.0"
       commit
       checkout main
       commit type: HIGHLIGHT
       commit
       merge develop
       commit
       branch featureA
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "029_customizing_branch_colors",
        title = "Customizing branch colors",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
      'git0': '#ff0000'
      'git1': '#00ff00'
      'git2': '#0000ff'
      'git3': '#ff00ff'
      'git4': '#00ffff'
      'git5': '#ffff00'
      'git6': '#ff00ff'
      'git7': '#00ffff'
---
       gitGraph
       commit
       branch develop
       commit tag:"v1.0.0"
       commit
       checkout main
       commit type: HIGHLIGHT
       commit
       merge develop
       commit
       branch featureA
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "030_customizing_branch_label_colors",
        title = "Customizing branch label colors",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
    'gitBranchLabel0': '#ffffff'
    'gitBranchLabel1': '#ffffff'
    'gitBranchLabel2': '#ffffff'
    'gitBranchLabel3': '#ffffff'
    'gitBranchLabel4': '#ffffff'
    'gitBranchLabel5': '#ffffff'
    'gitBranchLabel6': '#ffffff'
    'gitBranchLabel7': '#ffffff'
    'gitBranchLabel8': '#ffffff'
    'gitBranchLabel9': '#ffffff'
---
  gitGraph
    checkout main
    branch branch1
    branch branch2
    branch branch3
    branch branch4
    branch branch5
    branch branch6
    branch branch7
    branch branch8
    branch branch9
    checkout branch1
    commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "031_customizing_commit_colors",
        title = "Customizing Commit colors",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
    commitLabelColor: '#ff0000'
    commitLabelBackground: '#00ff00'
---
       gitGraph
       commit
       branch develop
       commit tag:"v1.0.0"
       commit
       checkout main
       commit type: HIGHLIGHT
       commit
       merge develop
       commit
       branch featureA
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "032_customizing_commit_label_font_size",
        title = "Customizing Commit Label Font Size",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
    commitLabelColor: '#ff0000'
    commitLabelBackground: '#00ff00'
    commitLabelFontSize: '16px'
---
       gitGraph
       commit
       branch develop
       commit tag:"v1.0.0"
       commit
       checkout main
       commit type: HIGHLIGHT
       commit
       merge develop
       commit
       branch featureA
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "033_customizing_tag_label_font_size",
        title = "Customizing Tag Label Font Size",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
    commitLabelColor: '#ff0000'
    commitLabelBackground: '#00ff00'
    tagLabelFontSize: '16px'
---
       gitGraph
       commit
       branch develop
       commit tag:"v1.0.0"
       commit
       checkout main
       commit type: HIGHLIGHT
       commit
       merge develop
       commit
       branch featureA
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "034_customizing_tag_colors",
        title = "Customizing Tag colors",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
    tagLabelColor: '#ff0000'
    tagLabelBackground: '#00ff00'
    tagLabelBorder: '#0000ff'
---
       gitGraph
       commit
       branch develop
       commit tag:"v1.0.0"
       commit
       checkout main
       commit type: HIGHLIGHT
       commit
       merge develop
       commit
       branch featureA
       commit
        """.trimIndent(),
    ),
    MermaidGitGraphDocCase(
        id = "035_customizing_highlight_commit_colors",
        title = "Customizing Highlight commit colors",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
    'gitInv0': '#ff0000'
---
       gitGraph
       commit
       branch develop
       commit tag:"v1.0.0"
       commit
       checkout main
       commit type: HIGHLIGHT
       commit
       merge develop
       commit
       branch featureA
       commit
        """.trimIndent(),
    ),
)
