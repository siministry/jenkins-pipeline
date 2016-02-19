/**
	* The sandbox environment does not allow us to check for the existence of the variables.
	* So we have to force them to some predefined state, so we know when they are supplied by a trigger or not.
	* The sourceBranch, sourceCommitHash and targetBranch originate from the stash-pullrequest-builder plugin
  * (https://wiki.jenkins-ci.org/display/JENKINS/Stash+pullrequest+builder+plugin)
	*/
properties([[$class: 'ParametersDefinitionProperty', parameterDefinitions: [
	[$class: 'StringParameterDefinition', defaultValue: 'empty', description: '', name: 'push_commit'],
	[$class: 'StringParameterDefinition', defaultValue: 'nightly', description: '', name: 'flowType'],
	[$class: 'StringParameterDefinition', defaultValue: 'empty', description: '', name: 'sourceBranch'],
	[$class: 'StringParameterDefinition', defaultValue: 'empty', description: '', name: 'sourceCommitHash'],
	[$class: 'StringParameterDefinition', defaultValue: 'empty', description: '', name: 'targetBranch']]]
])

/**
  * The Multi-Branch plugin will generate the jobs for us with a name based upon the branch name.
	* This however, does not contain 'origin/', so we have to filter this out.
	*/
def triggerBranchNormalized = "$sourceBranch"
def triggerBranch = "$sourceBranch"
if (triggerBranchNormalized.startsWith('origin')) {
    triggerBranchNormalized = triggerBranch.substring(7, triggerBranch.length())
}

// It also replaces the the slash in branch names - like feature/feature-x - with %252F.
def jobToTrigger = triggerBranchNormalized.replace("/", "%252F")
jobToTrigger = "$projectWorkflowFolder/$jobToTrigger"

build job: "$jobToTrigger",
parameters: [
	[$class: 'StringParameterValue', name: 'semVer', value: 'prerelease'],
 	[$class: 'StringParameterValue', name: 'push_commit', value: "$push_commit"],
 	[$class: 'StringParameterValue', name: 'flowType', value: "$flowType"],
 	[$class: 'StringParameterValue', name: 'sourceBranch', value: "$sourceBranch"],
 	[$class: 'StringParameterValue', name: 'sourceCommitHash', value: "$sourceCommitHash"],
 	[$class: 'StringParameterValue', name: 'targetBranch', value: "$targetBranch"]
 ], wait: false
