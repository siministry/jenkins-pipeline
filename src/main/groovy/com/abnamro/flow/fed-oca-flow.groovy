package com.abnamro.flow

/**
 * This is an example flow for a npm/gulp front-end application based upon AngularJS.
 * These properties have to be here, they actually get saved after the first execection.
 * And they become normal Jenkins Job Parameters. Which is why we use "hidden", as we don't actually want to adjust them during a manual build.
 */

properties(
    [
        [$class: 'ParametersDefinitionProperty', parameterDefinitions: [
            [$class: 'ChoiceParameterDefinition',
                choices: 'nightly\npush\nrelease',
                description: '<h3>Type of Flow that should be executed by the Workflow.</h3>',
                name: 'flowType'
            ],
            [$class: 'ChoiceParameterDefinition',
                choices: 'prerelease\nmajor\nminor\npatch\npremajor\npreminor\nprepatch',
                description: '<h3>NPM versioning parameter, default is prerelease. For specific version, use versionOverride parameter.</h3>',
                name: 'semVer'
            ],
            [$class: 'com.wangyin.parameter.WHideParameterDefinition', defaultValue: 'empty', description: '', name: 'push_commit'],
            [$class: 'com.wangyin.parameter.WHideParameterDefinition', defaultValue: 'empty', description: '', name: 'sourceBranch'],
            [$class: 'com.wangyin.parameter.WHideParameterDefinition', defaultValue: 'empty', description: '', name: 'sourceCommitHash'],
            [$class: 'com.wangyin.parameter.WHideParameterDefinition', defaultValue: 'empty', description: '', name: 'targetBranch'],
        ]
    ]]
)

// Assert all required values for the flow
assert "$flowType" != null
assert "$semVer" != null
assert "$env.componentScm" != null
assert "$env.componentBranch" != null
assert "$env.componentCredentialsId" != null
assert "$env.versionJob" != null
assert "$env.sonarJob" != null

def runFlow() {
	stage 'Executing Workflow'
    def steps
    node {
        pwd()
        unstash 'flowFiles'
        steps = load 'src\\main\\groovy\\com\\abnamro\\flow\\common\\Steps.groovy'
    }

    echo "BRANCH_NAME=$env.BRANCH_NAME"
    echo "push_commit=$push_commit"
    echo "flowType=$flowType"
    echo "sourceBranch=$sourceBranch"
    echo "sourceCommitHash=$sourceCommitHash"
    echo "targetBranch=$targetBranch"

    steps.determineGitFlowBranchType(env.BRANCH_NAME)

    steps.checkoutGitComponent()

    // Execute the required steps
    checkpoint "_Branch: $env.BRANCH_NAME"
    if ("$env.branchType" == "stable") {
        steps.updateBuildDescription("$flowType")
    } else {
        steps.updateBuildDescription("$env.BRANCH_NAME - $flowType")
    }
    steps.npmBuild()
    checkpoint 'Build & Test Completed'

    if ( ("$flowType" == "nightly" && ("$env.branchType" == "stable" || "$env.branchType" == "integration")) || "$flowType" == "release") {
        steps.versionStage()
        steps.publishStage()
    }
    checkpoint 'Version released in Nexus'

	if ( "$flowType" == "nightly" || "$flowType" == "release") {
		steps.sonarStage()
	}
    checkpoint 'SonarQube analysis done'

    if ("$flowType" == "release" || ("$flowType" == "nightly" && ("$env.branchType" == "stable" || "$env.branchType" == "integration")) ) {
        def utMachine = steps.deployUTStage()
        checkpoint 'Deployed to UT'
        steps.seleniumGridUTStage(utMachine)
    }

    steps.stashSuccessNotification()
}

return this;
