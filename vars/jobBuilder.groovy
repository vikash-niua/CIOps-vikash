import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

def call(Map params) {
    boolean createCategoryJobs = params.createCategoryJobs ?: false
    boolean sonarEnabled = params.sonarEnabled ?: false

    podTemplate(yaml: """
kind: Pod
metadata:
  name: build-utils
spec:
  containers:
  - name: build-utils
    image: egovio/build-utils:7-master-95e76687
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
    env:
      - name: DOCKER_UNAME
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: dockerUserName
      - name: DOCKER_UPASS
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: dockerPassword
      - name: DOCKER_NAMESPACE
        value: nudmcdg
      - name: DOCKER_GROUP_NAME  
        value: dev
    resources:
      requests:
        memory: "768Mi"
        cpu: "250m"
      limits:
        memory: "1024Mi"
        cpu: "500m"                
"""
    ) {
        node(POD_LABEL) {
        
        List<String> gitUrls = params.urls;
        String configFile = './build/build-config.yml';
        Map<String,List<JobConfig>> jobConfigMap=new HashMap<>();
        StringBuilder jobDslScript = new StringBuilder();
        List<String> allJobConfigs = new ArrayList<>();

        for (int i = 0; i < gitUrls.size(); i++) {
            String dirName = Utils.getDirName(gitUrls[i]);
            dir(dirName) {
                 git url: gitUrls[i], credentialsId: 'git_read'
                 def yaml = readYaml file: configFile;
                 List<JobConfig> jobConfigs = ConfigParser.populateConfigs(yaml.config, env);
                 jobConfigMap.put(gitUrls[i],jobConfigs);
                 allJobConfigs.addAll(jobConfigs);
            }
        }
        
        Set<String> repoSet = new HashSet<>();
        String repoList = "";

        List<String> folders = Utils.foldersToBeCreatedOrUpdated(allJobConfigs, env);
                  for (int j = 0; j < folders.size(); j++) {
                      jobDslScript.append("""
                          folder("${folders[j]}")
                          """);
                    }

        for (Map.Entry<Integer, String> entry : jobConfigMap.entrySet()) {   

            List<JobConfig> jobConfigs = entry.getValue();

        for (int i = 0; i < jobConfigs.size(); i++) {

            for(int j=0; j<jobConfigs.get(i).getBuildConfigs().size(); j++){
                BuildConfig buildConfig = jobConfigs.get(i).getBuildConfigs().get(j);
                repoSet.add(buildConfig.getImageName());                    
            }  

            repoList = String.join(",", repoSet);     

            jobDslScript.append("""
            pipelineJob("${jobConfigs.get(i).getName()}") {
                logRotator(-1, 4, -1, -1)
                parameters {  
                  gitParameterDefinition {
                        name('BRANCH')
                        type('PT_BRANCH')
                        description('')
                        branch('')
                        useRepository('')
                        defaultValue('niua-dev-2.0')
                        branchFilter('origin/(.*)')
                        tagFilter('*')
                        sortMode('ASCENDING_SMART')
                        selectedValue('DEFAULT')
                        quickFilterEnabled(true)
                        listSize('5')
                }
                  booleanParam('ALT_REPO_PUSH', false, 'Check to push images to GCR')
                  booleanParam('WANNA_DEPLOY', true, 'Trigger deployment after successful build')
                  booleanParam('SONAR_ENABLED', ${sonarEnabled}, 'Run SonarQube analysis before build')
            }
                definition {
                    cpsScm {
                        scm {
                            git{
                                remote {
                                    url("${entry.getKey()}")
                                    credentials('git_read')
                                } 
                                branch ('\${BRANCH}')
                                scriptPath('Jenkinsfile')
                                extensions { }
                            }
                        }

                    }
                }
            }
""");
        }

            String gitUrlForRouter = entry.getKey();
            String routerToken = Utils.getDirName(gitUrlForRouter);
            String webhookToken = System.getenv("GITHUB_WEBHOOK_TOKEN") ?: routerToken;
            jobDslScript.append("""
            pipelineJob("Router") {
                logRotator(-1, 5, -1, -1)
                triggers {
                    genericTrigger {
                        genericVariables {
                            genericVariable {
                                key("REF")
                                value('\$.ref')
                            }
                            genericVariable {
                                key("BEFORE")
                                value('\$.before')
                            }
                            genericVariable {
                                key("AFTER")
                                value('\$.after')
                            }
                            genericVariable {
                                key("ADDED_FILES")
                                value('\$.commits[*].added')
                            }
                            genericVariable {
                                key("MODIFIED_FILES")
                                value('\$.commits[*].modified')
                            }
                            genericVariable {
                                key("REMOVED_FILES")
                                value('\$.commits[*].removed')
                            }
                        }
                        token("${webhookToken}")
                        printContributedVariables(true)
                        printPostContent(true)
                        silentResponse(false)
                    }
                }
                definition {
                    cps {
                        script(\"\"\"
                        library 'ci-libs'
                        routerJob(
                            gitUrl: '${gitUrlForRouter}',
                            credentialsId: 'git_read',
                            apiCredentialsId: 'git_read_token',
                            configFile: 'build/build-config.yml',
                            agentLabel: 'built-in'
                        )
                        \"\"\")
                    }
                }
            }
""");
        }

        // Generate category-wise build+deploy jobs if flag is set
        if (createCategoryJobs && !allJobConfigs.isEmpty()) {
            Set<String> categories = new LinkedHashSet<>()
            String categoryPrefix = "builds/upyog/"
            for (JobConfig jc : allJobConfigs) {
                String name = jc.getName()
                if (name.startsWith(categoryPrefix)) {
                    String remaining = name.substring(categoryPrefix.length())
                    String category = remaining.contains("/") ? remaining.substring(0, remaining.indexOf("/")) : ""
                    if (category) {
                        categories.add(category)
                    }
                }
            }

            if (!categories.isEmpty()) {
                jobDslScript.append("""
                    folder("categories")
                """.stripIndent())

                for (String cat : categories) {
                    String repoUrlForCategory = gitUrls.isEmpty() ? '' : gitUrls.get(0);
                    jobDslScript.append("""
                    pipelineJob("categories/${cat}") {
                        logRotator(-1, 5, -1, -1)
                        definition {
                            cps {
                                script(\"\"\"
                                library 'ci-libs'
                                categoryPipeline(
                                    category: '${cat}',
                                    repoUrl: '${repoUrlForCategory}'
                                )
                                \"\"\")
                            }
                        }
                    }
                """)
                }
            }
        }

        stage('Building jobs') {

    echo "===== GENERATED DSL START ====="
    echo jobDslScript.toString()
    echo "===== GENERATED DSL END ====="

    writeFile(
        file: 'generated.dsl',
        text: jobDslScript.toString()
    )

    sh 'ls -ltr'
    sh 'wc -l generated.dsl'

    jobDsl(
        targets: 'generated.dsl',
        removedJobAction: 'IGNORE',
        removedViewAction: 'IGNORE'
    )
}

        stage('Creating Repositories in DockerHub') {
                    withEnv(["REPO_LIST=${repoList}"
                    ]) {
                        container(name: 'build-utils', shell: '/bin/sh') {
                            sh (script:'sh /tmp/scripts/create_repo.sh')
                           //sh (script:'echo \$REPO_LIST')
                        }
                    }
        }
                

    }

}
}