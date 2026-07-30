import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.BuildConfig
import org.egov.jenkins.models.JobConfig

import static org.egov.jenkins.ConfigParser.getCommonBasePath

library 'ci-libs'

def call(Map pipelineParams) {
    // Allocate more memory for finance service (requires 10Gi+ for Maven build)
    String kanikoMemoryLimit = env.JOB_NAME?.contains("egov-finance") ? "10Gi" : "6Gi"
    String kanikoMemoryRequest = env.JOB_NAME?.contains("egov-finance") ? "6Gi" : "3Gi"

    podTemplate(yaml: """
kind: Pod
metadata:
  name: kaniko
spec:
  containers:
  - name: kaniko
    image: gcr.io/kaniko-project/executor:v1.17.0-debug
    imagePullPolicy: IfNotPresent
    workingDir: /home/jenkins/agent
    command:
    - /busybox/cat
    tty: true
    env:
      - name: GIT_ACCESS_TOKEN
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken 
      - name: token
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken
      - name: SLACK_WEBHOOK
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: slackWebhook                                     
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /kaniko/.docker
      - name: kaniko-cache
        mountPath: /cache            
    resources:
      requests:
        memory: "${kanikoMemoryRequest}"
        cpu: "750m"
      limits:
        memory: "${kanikoMemoryLimit}"
        cpu: "1500m"      
  - name: git
    image: docker.io/egovio/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true        
  - name: sonar-scanner
    image: sonarsource/sonar-scanner-cli:11.1
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
    env:
      - name: SONAR_HOST_URL
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: sonarHostUrl
            optional: true
      - name: SONAR_TOKEN
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: sonarToken
            optional: true
  - name: jnlp
    env:
      - name: SLACK_WEBHOOK
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: slackWebhook
      - name: SLACK_WEBHOOK_FAIL
        valueFrom:
           secretKeyRef:
            name: jenkins-credentials
            key: slackWebhookFail
  volumes:
  - name: kaniko-cache
    persistentVolumeClaim:
      claimName: kaniko-cache-claim
      readOnly: true        
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: dockerConfigJson
              path: config.json          
"""
    ) {
        node(POD_LABEL) {

            def scmVars = checkout scm
            String REPO_NAME = env.REPO_NAME ? env.REPO_NAME : "docker.io/nudmcdg";         
            String GCR_REPO_NAME = "asia.gcr.io/digit-egov";
            def yaml = readYaml file: pipelineParams.configFile;
            List<JobConfig> jobConfigs = ConfigParser.parseConfig(yaml, env);
            String serviceCategory = null;
            String buildNum = null;

            for(int i=0; i<jobConfigs.size(); i++){
                JobConfig jobConfig = jobConfigs.get(i)

                stage('Parse Latest Git Commit') {
                    withEnv(["BUILD_PATH=${jobConfig.getBuildConfigs().get(0).getWorkDir()}",
                             "PATH=alpine:$PATH"
                    ]) {
                        container(name: 'git', shell: '/bin/sh') {
                             // Debug: Check Java and Maven versions in the container
                             sh 'echo "==== JAVA VERSION ===="'
                             sh 'java -version || echo "Java not found"'
                             sh 'echo "==== MAVEN VERSION ===="'
                             sh 'mvn -v || echo "Maven not found"'

                            // Existing logic
                        scmVars['VERSION'] = sh (script:
                               '/scripts/get_application_version.sh ${BUILD_PATH}',
                               returnStdout: true).trim()
                        scmVars['ACTUAL_COMMIT'] = sh (script:
                               '/scripts/get_folder_commit.sh ${BUILD_PATH}',
                               returnStdout: true).trim()
                        scmVars['BRANCH'] = scmVars['GIT_BRANCH'].replaceFirst("origin/", "")
                        }
                    }
                }
                String builtImage = null
                String slackStage = ''
                String slackImage = ''
                String slackStatus = 'BUILD_PASSED'
                String slackTimestamp = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))
                String commitMessage = scmVars.GIT_COMMIT ? scmVars.GIT_COMMIT.take(8) : ''

                try {
                    slackStage = 'SonarQube Analysis'
                    stage('SonarQube Analysis') {
                        if (env.SONAR_ENABLED?.toBoolean()) {
                        container(name: 'sonar-scanner', shell: '/bin/sh') {
                            String workDir = jobConfig.getBuildConfigs().get(0).getWorkDir()
                            String projectKey = jobConfig.getBuildConfigs().get(0).getImageName()
                            sh """
                                echo "===== SONARQUBE ANALYSIS ====="
                                echo "Project: ${projectKey}"
                                echo "Branch: ${scmVars.BRANCH}"

                                cd \${WORKSPACE}/${workDir}

                                sonar-scanner \
                                    -Dsonar.projectKey=${projectKey} \
                                    -Dsonar.projectName=${projectKey} \
                                    -Dsonar.branch.name=${scmVars.BRANCH} \
                                    -Dsonar.sources=. \
                                    -Dsonar.java.binaries=. \
                                    -Dsonar.scm.provider=git \
                                    -Dsonar.host.url=\${SONAR_HOST_URL} \
                                    -Dsonar.login=\${SONAR_TOKEN} || echo "SonarQube scan completed (exit code ignored)"
                            """
                        }
                        } else {
                        echo "SonarQube analysis disabled (SONAR_ENABLED=false). Skipping."
                        }
                    }
                    slackStage = 'Build with Kaniko'
                    slackImage = builtImage ?: 'N/A'
                    stage('Build with Kaniko') {
                    withEnv(["PATH=/busybox:/kaniko:$PATH"
                    ]) {
                        container(name: 'kaniko', shell: '/busybox/sh') {
                            for(int j=0; j<jobConfig.getBuildConfigs().size(); j++){
                                BuildConfig buildConfig = jobConfig.getBuildConfigs().get(j)
                                echo "${buildConfig.getWorkDir()} ${buildConfig.getDockerFile()}"
                                if( ! fileExists(buildConfig.getWorkDir()) || ! fileExists(buildConfig.getDockerFile()))
                                    throw new Exception("Working directory / dockerfile does not exist!");

                                String workDir = buildConfig.getWorkDir().replaceFirst(getCommonBasePath(buildConfig.getWorkDir(), buildConfig.getDockerFile()), "./")
                                String image = null;
                                if(scmVars.BRANCH.equalsIgnoreCase("master")) {
                                  image = "${REPO_NAME}/${buildConfig.getImageName()}:v${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}";
                                } else {
                                  image = "${REPO_NAME}/${buildConfig.getImageName()}:${scmVars.BRANCH}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}";
                                }
                                builtImage = image 
                                serviceCategory = buildConfig.getServiceCategoryName();  // Dashboard
                                buildNum = "${scmVars.VERSION}"; // Dashboard
                                String noPushImage = env.NO_PUSH ? env.NO_PUSH : false;
                                echo "ALT_REPO_PUSH ENABLED: ${ALT_REPO_PUSH}"
                                 if(env.ALT_REPO_PUSH.equalsIgnoreCase("true")){
                                  String gcr_image = "${GCR_REPO_NAME}/${buildConfig.getImageName()}:${env.BUILD_NUMBER}-${scmVars.BRANCH}-${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}";
                                  sh """
                                    echo \"Attempting to build image,  ${image}\"
                                    /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                    --build-arg WORK_DIR=${workDir} \
                                    --custom-platform=linux/amd64 \
                                    --build-arg token=\$GIT_ACCESS_TOKEN \
                                    --cache=true --cache-dir=/cache \
                                    --single-snapshot=false \
                                    --snapshotMode=redo \
                                    --destination=${image} \
                                    --destination=${gcr_image} \
                                    --no-push=${noPushImage} \
                                    --cache-repo=nudmcdg/cache/cache
                                  """  
                                  echo "${image} and ${gcr_image} pushed successfully!!"                              
                                }
                                // else{
                                // sh """
                                //     echo "===== DEBUG START ====="
                                //     echo "PWD"
                                //     pwd
                                //     echo "===== CURRENT DIR ====="
                                //     ls -la
                                //     echo "===== BUILD CONTEXT ====="
                                //     ls -la ${buildConfig.getContext()}
                                //     echo "DOCKERFILE=${buildConfig.getDockerFile()}"
                                //     echo "CONTEXT=${buildConfig.getContext()}"
                                //     echo "WORKDIR=${workDir}"
                                //     echo "${buildConfig}"
                                //     echo "===== MIGRATION DIR ====="
                                //     ls -la ${buildConfig.getContext()}/migration
                                //     echo "===== MIGRATION MAIN ====="
                                //     ls -la ${buildConfig.getContext()}/migration/main
                                //     echo "===== MIGRATE SH ====="
                                //     ls -la ${buildConfig.getContext()}/migrate.sh
                                //     echo "===== DOCKERFILE ====="
                                //     cat ${buildConfig.getDockerFile()}
                                //     echo "===== DEBUG END ====="
                                // """
                                // sh """
                                //     echo \"Attempting to build image,  ${image}\"
                                //     /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                //     --build-arg WORK_DIR=${workDir} \
                                //     --build-arg token=\$GIT_ACCESS_TOKEN \
                                //     --cache=true --cache-dir=/cache \
                                //     --single-snapshot=true \
                                //     --snapshotMode=time \
                                //     --destination=${image} \
                                //     --no-push=${noPushImage} \
                                //     --cache-repo=nudmcdg/cache/cache
                                // """
                                // echo "${image} pushed successfully!"
                                // }  
                                else{
                                  sh """
                                  echo "===== FILE CHECK ====="
                                  echo "CONTEXT = ${buildConfig.getContext()}"
                                  find ${buildConfig.getContext()} -type f | grep migrate.sh || true
                                  find ${buildConfig.getContext()} -type d | grep migration || true
                                  echo "===== MIGRATE.SH ====="
                                  ls -la ${buildConfig.getContext()}/migrate.sh || true
                                  echo "===== MIGRATION MAIN ====="
                                  ls -la ${buildConfig.getContext()}/migration/main || true
                                  echo "===== DOCKERFILE ====="
                                  cat ${buildConfig.getDockerFile()}
                              """
                              sh """
                                  echo \"Attempting to build image,  ${image}\"
                                  /kaniko/executor -f `pwd`/${buildConfig.getDockerFile()} -c `pwd`/${buildConfig.getContext()} \
                                  --build-arg WORK_DIR=${workDir} \
                                  --custom-platform=linux/amd64 \
                                  --build-arg token=\$GIT_ACCESS_TOKEN \
                                  --cache=true --cache-dir=/cache \
                                  --single-snapshot=false \
                                  --snapshotMode=redo \
                                  --destination=${image} \
                                  --no-push=${noPushImage} \
                              """
                              echo "${image} pushed successfully!"
                              // echo "WANNA_DEPLOY = ${env.WANNA_DEPLOY}"
                              }                              
                            }
                        }
                    }
                }
                slackImage = builtImage ?: 'N/A'
                slackStage = 'Deploy'
                stage('Deploy') {
                if(env.WANNA_DEPLOY?.toBoolean()) {

                  if(!builtImage?.trim()) {
                        error("WANNA_DEPLOY=true but builtImage is empty")
                    }

                    echo "Deploying image: ${builtImage}"
                    slackImage = builtImage

                    def deployResult = build(
                        job: "deployments/deploy-to-qa",
                        wait: true,
                        parameters: [
                            string(
                            name: "Images",
                            value: builtImage
                            )
                        ]
                    )
                    
                    slackStatus = 'FULL_SUCCESS'

                } else {
                echo "Deployment skipped. WANNA_DEPLOY was not selected."
                slackStatus = 'DEPLOY_SKIPPED'
            }
        }

                } catch (Exception slackErr) {
                    deleteDir()
                    slackImage = slackImage ?: 'N/A'
                    def slackColor = ''
                    def slackBlocks = []
                    if (slackStage == 'SonarQube Analysis') {
                        slackColor = 'danger'
                        slackBlocks = [
                            [type: 'header', text: [type: 'plain_text', text: "❌ SonarQube Analysis Failed"]],
                            [type: 'section', fields: [
                                [type: 'mrkdwn', text: "*Stage:*\n${slackStage}"],
                                [type: 'mrkdwn', text: "*Commit:*\n${commitMessage}"]
                            ]],
                            [type: 'section', text: [type: 'mrkdwn', text: "*Error:*\n${slackErr.message.take(300)}"]],
                            [type: 'context', elements: [
                                [type: 'mrkdwn', text: "🕐 ${slackTimestamp} UTC"]
                            ]]
                        ]
                    } else if (slackStage == 'Build with Kaniko') {
                        slackColor = 'danger'
                        slackBlocks = [
                            [type: 'header', text: [type: 'plain_text', text: "❌ Build Failed"]],
                            [type: 'section', fields: [
                                [type: 'mrkdwn', text: "*Stage:*\n${slackStage}"],
                                [type: 'mrkdwn', text: "*Commit:*\n${commitMessage}"]
                            ]],
                            [type: 'section', text: [type: 'mrkdwn', text: "*Error:*\n${slackErr.message.take(300)}"]],
                            [type: 'context', elements: [
                                [type: 'mrkdwn', text: "🕐 ${slackTimestamp} UTC"]
                            ]]
                        ]
                    } else if (slackStatus == 'BUILD_PASSED' && slackStage == 'Deploy') {
                        slackColor = 'warning'
                        slackBlocks = [
                            [type: 'header', text: [type: 'plain_text', text: "⚠️ Build Passed, Deploy Failed"]],
                            [type: 'section', fields: [
                                [type: 'mrkdwn', text: "*Stage:*\n${slackStage}"],
                                [type: 'mrkdwn', text: "*Image:*\n`${slackImage}`"]
                            ]],
                            [type: 'section', fields: [
                                [type: 'mrkdwn', text: "*Commit:*\n${commitMessage}"],
                                [type: 'mrkdwn', text: "*Error:*\n${slackErr.message.take(300)}"]
                            ]],
                            [type: 'context', elements: [
                                [type: 'mrkdwn', text: "🕐 ${slackTimestamp} UTC"]
                            ]]
                        ]
                    } else if (slackStatus == 'DEPLOY_SKIPPED') {
                        slackColor = 'good'
                        slackBlocks = [
                            [type: 'header', text: [type: 'plain_text', text: "✅ Build Successful (Deploy Skipped)"]],
                            [type: 'section', fields: [
                                [type: 'mrkdwn', text: "*Image:*\n`${slackImage}`"],
                                [type: 'mrkdwn', text: "*Commit:*\n${commitMessage}"]
                            ]],
                            [type: 'context', elements: [
                                [type: 'mrkdwn', text: "🕐 ${slackTimestamp} UTC"]
                            ]]
                        ]
                    }
                    def slackPayload = groovy.json.JsonOutput.toJson([attachments: [[color: slackColor, blocks: slackBlocks]]])
                    writeFile file: 'slack-payload.json', text: slackPayload
                    def failureWebhook = (slackStage == 'SonarQube Analysis' || slackStage == 'Build with Kaniko' || slackStage == 'Deploy') ? "\${SLACK_WEBHOOK_FAIL}" : "\${SLACK_WEBHOOK}"
                    sh "curl -s -X POST -H 'Content-type: application/json' --data @slack-payload.json ${failureWebhook} || true"
                    throw slackErr
                }

                // Send success alert
                if (slackStatus == 'FULL_SUCCESS') {
                    def slackBlocks2 = [
                        [type: 'header', text: [type: 'plain_text', text: "✅ Build and Deploy Successful"]],
                        [type: 'section', fields: [
                            [type: 'mrkdwn', text: "*Image:*\n$slackImage"],
                            [type: 'mrkdwn', text: "*Commit:*\n$commitMessage"]
                        ]],
                        [type: 'context', elements: [
                            [type: 'mrkdwn', text: "<${env.BUILD_URL}|View Build>  🕐 $slackTimestamp UTC"]
                        ]]
                    ]
                    def slackPayload = groovy.json.JsonOutput.toJson([attachments: [[color: 'good', blocks: slackBlocks2]]])
                    writeFile file: 'slack-payload.json', text: slackPayload
                    sh "curl -s -X POST -H 'Content-type: application/json' --data @slack-payload.json \${SLACK_WEBHOOK} || true"
                } else if (slackStatus == 'DEPLOY_SKIPPED') {
                    def slackBlocks_skipped = [
                        [type: 'header', text: [type: 'plain_text', text: "ℹ️ Build Successful (Deploy Skipped)"]],
                        [type: 'section', fields: [
                            [type: 'mrkdwn', text: "*Image:*\n`$slackImage`"],
                            [type: 'mrkdwn', text: "*Commit:*\n$commitMessage"]
                        ]],
                        [type: 'context', elements: [
                            [type: 'mrkdwn', text: "🕐 $slackTimestamp UTC"]
                        ]]
                    ]
                    def slackPayload = groovy.json.JsonOutput.toJson([attachments: [[color: 'good', blocks: slackBlocks_skipped]]])
                    writeFile file: 'slack-payload.json', text: slackPayload
                    sh "curl -s -X POST -H 'Content-type: application/json' --data @slack-payload.json \${SLACK_WEBHOOK} || true"
                    }

                // Clean workspace so it doesn't accumulate on controller PVC
                deleteDir()

                // stage ("Update dashboard") {
                //         environmentDashboard {
                //             environmentName(scmVars.BRANCH)  
                //             componentName(serviceCategory)
                //             buildNumber(buildNum)
                //             //buildJob(String buildJob)
                //             //packageName(String packageName)
                //             //addColumns(true)
                //             //Date now = new Date()                                
                //             //columns(String Date, now.format("yyMMdd.HHmm", TimeZone.getTimeZone('UTC'))) 
                //     }    
                // }    
            }
        }
    }

    // Clean workspace@script (compiled pipeline cache - 1.3GB per job)
    // Runs on built-in node since the build pod is already torn down
    node('built-in') {
            def jobDir = "/var/jenkins_home/jobs/${env.JOB_NAME.replace('/', '/jobs/')}"
            sh "rm -rf ${jobDir}/workspace@script 2>/dev/null || true"
        }
    }
