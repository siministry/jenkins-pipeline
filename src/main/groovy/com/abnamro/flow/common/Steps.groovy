package com.abnamro.flow.common

// STAGES
def checkoutGitComponent() {
    stage 'SCM Checkout'
    node {
        try {
            wrap([$class: 'TimestamperBuildWrapper']) {
                timeout(time: 90, unit: 'SECONDS') {
                    ws(getWorkspace()) {
                        deleteDir()

                        // Assert all required values for the method
                        assert "$flowType" != null
                        assert "$env.BRANCH_NAME" != null
                        assert "$push_commit" != null
                        assert "$sourceBranch" != null
                        assert "$sourceCommitHash" != null
                        assert "$targetBranch" != null
                        assert "$env.componentScm" != null
                        assert "$env.componentCredentialsId" != null

                        if ('empty' == "$sourceBranch") {
                            if ("$push_commit" == "empty") {
                                scmCheckoutBranch(env.componentCredentialsId, env.componentScm, env.BRANCH_NAME)
                            } else {
                                scmCheckoutCommit(env.componentCredentialsId, env.componentScm, "$push_commit")
                            }

                        } else {
                            scmCheckoutPullrequest(env.componentCredentialsId, env.componentScm, "$sourceCommitHash", "$targetBranch")
                        }
                        stash includes: '**', name: 'componentFiles'
                    }
                }
            }
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'failed to checkout'
        }
    }
}

def npmBuild() {
    stage 'Build & Test'
    node {
        try {
            wrap([$class: 'TimestamperBuildWrapper']) {
                timeout(time: 30, unit: 'MINUTES') {
                    ws(getWorkspace()) {
                        pwd()
                        deleteDir()
                        unstash 'componentFiles'

                        // Our Windows buildslaves are offline, so we require the phantomjs pushed to a path directly.
                        // Leaving this old workaround in here, to show how withEnv can be utilized for such tools.
                        withEnv(["PHANTOMJSZIP=$env.phantomjsZip"]) {
                            bat '''copy %PHANTOMJSZIP% C:\\Windows\\TEMP\\phantomjs\\'''
                        }
                        def phantomjsPath = 'C:\\Windows\\TEMP\\phantomjs\\'
                        if (fileExists('D:\\TEMP')) {
                            writeFile encoding: 'UTF-8', file: 'D:\\TEMP\\phantomjs\\temp.txt', text: 'temp'
                            withEnv(["PHANTOMJSZIP=$env.phantomjsZip"]) {
                                bat 'copy %PHANTOMJSZIP% D:\\TEMP\\phantomjs\\'
                            }
                            phantomjsPath = 'D:\\TEMP\\phantomjs\\'
                        }
                        def workspace = pwd()
                        withEnv(["WORKSPACE=$workspace", "PATH+PAHTOMJS=$phantomjsPath", "PATHEXT+PAHTOMJS=ZIP"]) {
                            bat '''npm set progress=false'''
                            bat "npm install --cache=%WORKSPACE%/npmcache"
                        }
                        stash includes: '**', name: 'workspace'
                    }
                }

            }
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'build failed'
        }
    }
}
// Executing protractor tests on our UT deployment with a Selenium Grid
def seleniumGridUTStage(utMachine) {
    stage 'Protractor UIT @UT'
    node {
        try {
            def utMachineUrl = getUtMachineUrl(utMachine)
            ws(getWorkspace()) {
                pwd()
                deleteDir()
                withEnv(["URL=$utMachineUrl"]) {
                    unstash 'workspace'
                    bat '''npm set progress=false'''
                    bat '''npm install protractor'''
                    bat '''npm install protractor-html-screenshot-reporter'''
                    bat '''npm install del'''
                    bat '''node_modules\\.bin\\protractor test\\integration\\protractor.integration.conf.js --baseUrl %URL%'''
                }
            }
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'protractor tests failed'
        }
    }
}

/**
  * Our SonarQube configuration is managed by Jenkins.
  * This means we cannot rely on any build tool plugin to do the sonar analysis run for us.
  * But, the SonarQube plugin in Jenkins is not compatible yet with Jenkins Pipeline.
  * So we use an external job, see the Jenkins Job Builder configuration for how to configure this job to be flexible enough for all the branches.
  */

def sonarStage() {
    stage 'SonarQube Analysis'
    // Because SonarQube plugin is not yet supported: http://jira.sonarsource.com/browse/SONARJNKNS-213
    timeout(time: 30, unit: 'MINUTES') {
        try {
            build job: "$env.sonarJob", parameters: [[$class: 'StringParameterValue', name: 'branch', value: "$env.BRANCH_NAME"]]
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'sonarqube analysis failed'
        }
    }
}

/**
  * Versioning the NPM way.
  * Here we also use an external job, as with GIT we have to push the changes to the repository.
  * You can have this done by a commandline call instead.
  */
def versionStage() {
    stage 'Version'
    timeout(time: 30, unit: 'SECONDS') {
        // Because GIT Publisher is not yet supported: https://issues.jenkins-ci.org/browse/JENKINS-28335
        try {
            build job: "$env.versionJob", parameters: [[$class: 'StringParameterValue', name: 'semVer', value: "$semVer"], [$class: 'StringParameterValue', name: 'branch', value: "$env.BRANCH_NAME"]]
            node {
                step([$class: 'CopyArtifact', filter: 'version.txt', fingerprintArtifacts: true, projectName: "$env.versionJob", selector: [$class: 'LastCompletedBuildSelector']])
                stash includes: 'version.txt', name: 'version'
            }
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'versioning failed'
        }
    }

}

/**
 * This again used NPM, but adding more alternative based upon a parameter for a maven or gradle release build is simple.
 */
def publishStage() {
    stage 'Publish to Nexus'
    node {
        wrap([$class: 'TimestamperBuildWrapper']) {
            timeout(time: 30, unit: 'MINUTES') {
                try {
                    ws(getWorkspace()) {
                        deleteDir()
                        unstash 'workspace'
                        git changelog: false, credentialsId: "$env.componentCredentialsId", poll: false, url: "$env.componentScm", branch: "$env.BRANCH_NAME", shallow: false
                        def workspace = pwd()
                        withEnv(["WORKSPACE=$workspace"]) {
                            bat '''npm set progress=false'''
                            bat "npm install --cache=%WORKSPACE%/npmcache"
                            bat '''npm publish --cache=%WORKSPACE%/npmcache'''
                        }
                    }
                } catch (err) {
                    echo "Caught: ${err}"
                    stashFaillureNotification()
                    error 'publish to nexus failed'
                }
            }
        }
    }
}

/**
  * Once in Nexus, we do not build the application anymore.
  * Any further step that requires the artifact, like deployments, should get it from Nexus.
  */
def retrieveArtifactFromNexusStage() {
    stage 'Retrieve Deploy Artifact'
    node {
        wrap([$class: 'TimestamperBuildWrapper']) {
            try {
                timeout(time: 30, unit: 'SECONDS') {
                    unstash 'version'
                    def versionRaw = readFile 'version.txt'
                    version = versionRaw.trim()
                    def downloadFile = "${env.nexusRepositoryUrl}${env.nexusComponentId}/-/${env.nexusComponentId}-${version}.tgz"

                    ws(getWorkspace()) {
                        withEnv(["DOWNLOADFILE=$downloadFile"]) {
                            bat '''powershell -Command (New-Object Net.WebClient).DownloadFile('%DOWNLOADFILE%', 'temp.tgz')'''
                        }
                        stash includes: 'temp.tgz', name: 'deployArtifact'
                    }
                }
            } catch (err) {
                echo "Caught: ${err}"
                stashFaillureNotification()
                error 'retrieve deploy artifact failed'
            }
        }
    }
}

/**
  * The standards and guidelines application is a static website.
  * In this step we upload it to a webhost.
  */
def publishDocumentationStage() {
    stage 'Publish Documentation'
    input 'Do you want to publish the documentation?'

    node('master') {
        wrap([$class: 'TimestamperBuildWrapper']) {
            try {
                unstash 'deployArtifact'
                echo '======|> Unpacking Deploy Archive <|======'
                sh '''tar xzvf temp.tgz'''

                echo '======|> Uploading Documentation to the Hosting server <|======'
                unstash 'version'
                def versionRaw = readFile 'version.txt'
                version = versionRaw.trim()

                dir('package/dist/') {
                    def latestUrl = "${env.sitePublishLocation}/${env.nexusComponentId}/latest/{}"
                    def versionUrl = "${env.sitePublishLocation}/${env.nexusComponentId}/${version}/{}"
                    withEnv(["latestUrl=$latestUrl", "versionUrl=$versionUrl"]) {
                        withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.sitePublishCredentialsId",
                                          usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                            sh '''find . -type f -exec curl -v --user $USERNAME:$PASSWORD --upload-file ./{} $latestUrl \\;'''
                            sh '''find . -type f -exec curl -v --user $USERNAME:$PASSWORD --upload-file ./{} $versionUrl \\;'''
                        }
                    }
                }
            } catch (err) {
                echo "Caught: ${err}"
                stashFaillureNotification()
                error 'publish documentation failed'
            }
        }
    }
}

/**
  * Deployment to an UT (develop in DTAP) environment.
  */
def deployUTStage() {
    stage 'Deploy UT'
    def deploymentVariables = input(
        id: 'deploymentVariables',
        message: 'Deploy to UT?',
        ok: 'Deploy',
        parameters: [
                [$class     : 'ChoiceParameterDefinition',
                 choices    : 'ut-72\nut-8561\nut-54\nut-53\nut-52\nut-51\nut-vm000264\nut-vm000269',
                 description: 'Please select your target environment to deploy the OCA',
                 name       : 'environment'
                ],
                [$class: 'StringParameterDefinition', description: 'Please enter your Unix user id which has access rights to deploy the package. ', name: 'username'],
                [$class: 'hudson.model.PasswordParameterDefinition', description: 'Please enter the passowrd of the above entered username.', name: 'password'],
                [$class: 'StringParameterDefinition', description: '''Please enter the user which has access rights to distribute the package to the CDA warehouse. The format of the distr-username is like \'devxxx\'.''', name: 'distr-username'],
                [$class: 'hudson.model.PasswordParameterDefinition', description: 'Please enter the password of the above \'distr-username\'.', name: 'distr-password'],
                [$class: 'StringParameterDefinition', description: '', name: 'options']
        ]
    )
    //[$class: 'StringParameterDefinition', description: 'Please enter the oca version which needs to be deployed in the target environment', name: 'oca-version'],
    node {
        try {
            wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: '', var: 'password'], [password: '', var: 'distr-password']]]) {
                wrap([$class: 'TimestamperBuildWrapper']) {
                    echo "environment=" + deploymentVariables['environment']
                    echo "oca-version=" + deploymentVariables['oca-version']
                    echo "username=" + deploymentVariables['username']
                    echo "distr-username=" + deploymentVariables['distr-username']
                    echo "options=" + deploymentVariables['options']
                    //echo "password=" + deploymentVariables['password']
                    //echo "distr-password=" + deploymentVariables['distr-password']

                    def environment= deploymentVariables['environment']
                    unstash 'version'
                    def versionRaw = readFile 'version.txt'
                    def ocaVersion= versionRaw.trim() //deploymentVariables['oca-version']
                    def username= deploymentVariables['username']
                    def distrUsername= deploymentVariables['distr-username']
                    def options= deploymentVariables['options']
                    def password= deploymentVariables['password']
                    def distrPassword= deploymentVariables['distr-password']

                    // Actual deployment lines removed due to exposing internal application/configuration.

                    return environment;
                }
            }
        } catch (err) {
            echo "Caught: ${err}"
            stashFaillureNotification()
            error 'deploy to ut failed'
        }

    }
}

// UTIL Methods
// While Jenkins-30744 is not fixed, we have do this. As Windows does not support "%" in folder names.
// note: this is a Windows problem
def getWorkspace() {
    pwd().replace("%2F", "_")
}

def scmCheckoutBranch(credentialsId, scmUrl, branch) {
    echo "Checking out branch: credentialsId=$credentialsId, branch=$branch, scmUrl=$scmUrl"
    git changelog: true, credentialsId: credentialsId, poll: false, url: scmUrl, branch: branch, shallow: true
    bat '''git rev-parse --verify HEAD > gitcommit.txt'''
    env.gitcommit = readFile('gitcommit.txt').trim()
}

def scmCheckoutCommit(credentialsId, scmUrl, commithash) {
    echo 'Checking out commit'
    checkout([$class: 'GitSCM', branches: [[name: "$commithash"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: "$credentialsId", url: "$scmUrl"]]])
    env.gitcommit = "$commithash"
}

def scmCheckoutPullrequest(credentialsId, scmUrl, commitHash, targetBranch) {
    echo 'Checking source branch & target branch'
    checkout changelog: true, poll: false, scm: [$class: 'GitSCM', branches: [[name: commitHash]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'origin', mergeTarget: targetBranch]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: credentialsId, url: scmUrl]]]
    env.gitcommit = commitHash
}

def stashSuccessNotification() {
    node {
        wrap([$class: 'TimestamperBuildWrapper']) {
            writeFile encoding: 'UTF8', file: 'build.json', text: "{\"state\": \"SUCCESSFUL\", \"key\": \"${env.JOB_NAME}\", \"name\": \"${env.BUILD_TAG}\", \"url\": \"${env.BUILD_URL}\"}"
            archive '*.json'
            stash includes: 'build.json', name: 'buildInfo'
        }
    }

    node('master') {
        wrap([$class: 'TimestamperBuildWrapper']) {
            url = "${env.notifyScmUrl}/rest/build-status/1.0/commits/${env.gitcommit}"
            echo "url? = ${url}"
            withEnv(["URL=$url"]) {
                withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.componentCredentialsId",
                                  usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                    unstash 'buildInfo'
                    sh '''curl -u $USERNAME:$PASSWORD -H "Content-Type: application/json" -X POST $URL -d @build.json'''
                }
            }
        }
    }
}

def stashFaillureNotification() {
    node {
        wrap([$class: 'TimestamperBuildWrapper']) {
            writeFile encoding: 'UTF8', file: 'build.json', text: "{\"state\": \"FAILED\", \"key\": \"${env.JOB_NAME}\", \"name\": \"${env.BUILD_TAG}\", \"url\": \"${env.BUILD_URL}\"}"
            archive '*.json'
            stash includes: 'build.json', name: 'buildInfo'
        }
    }

    node('master') {
        wrap([$class: 'TimestamperBuildWrapper']) {
            url = "${env.notifyScmUrl}/rest/build-status/1.0/commits/${env.gitcommit}"
            echo "url? = ${url}"
            withEnv(["URL=$url"]) {
                withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.componentCredentialsId",
                                  usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                    unstash 'buildInfo'
                    sh '''curl -u $USERNAME:$PASSWORD -H "Content-Type: application/json" -X POST $URL -d @build.json'''
                }
            }
        }
    }
}

def updateBuildDescription(String description) {
    node('master') {
        def url = "${env.BUILD_URL}submitDescription"
        withEnv(["URL=$url", "DESCRIPTION=$description"]) {
            withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: "$env.componentCredentialsId",
                              usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                sh '''curl --user $USERNAME:$PASSWORD --data "core:apply="  --data "description=$DESCRIPTION" --data "Submit=Submit" "$URL" '''
            }
        }
    }
}

def determineGitFlowBranchType(branch) {
    def branchType = 'bug'
    if (branch == "develop" || branch == "master" || branch.startsWith('release/')) {
        branchType = 'stable'
    } else if (branch.startsWith('feature/')) {
        branchType = 'feature'
    }
    return branchType
}

def getUtMachineUrl(utMachine) {
    return 'http://ut-machine.yourorg.com'
}

return this;
