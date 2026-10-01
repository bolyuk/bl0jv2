pipeline {
    agent any
    tools {
        jdk 'GraalVM-25'
        maven 'Default'
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn clean package'
            }
        }
        stage('Deploy to Nexus') {
            when {
                branch 'master'
            }
            steps {
                configFileProvider([configFile(fileId: '413c037e-55bf-4e38-96e3-0428513e6856', variable: 'MAVEN_SETTINGS')]) {
                    sh 'mvn -s $MAVEN_SETTINGS deploy -DskipTests'
                }
            }
        }
        stage('Gitea Release') {
            when {
                branch 'master'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: '81140011-98ba-4d15-b54e-ba082956122f', usernameVariable: 'GITEA_USER', passwordVariable: 'GITEA_TOKEN')]) {
                    sh '''
                        TAG="build-${BUILD_NUMBER}"
                        RELEASE_ID=$(curl -s -u "$GITEA_USER:$GITEA_TOKEN" -X POST \
                            "https://git.bolyuk.org/api/v1/repos/bolyuk/bl0jv2/releases" \
                            -H "Content-Type: application/json" \
                            -d "{\\"tag_name\\":\\"$TAG\\",\\"target_commitish\\":\\"master\\",\\"name\\":\\"$TAG\\",\\"body\\":\\"Automated build from Jenkins, commit $GIT_COMMIT\\",\\"draft\\":false,\\"prerelease\\":false}" \
                            | jq -r '.id')
                        curl -s -u "$GITEA_USER:$GITEA_TOKEN" -X POST \
                            "https://git.bolyuk.org/api/v1/repos/bolyuk/bl0jv2/releases/${RELEASE_ID}/assets?name=bl0jv2" \
                            -F "attachment=@bl0jv2-cli/target/bl0jv2"
                    '''
                }
            }
        }
    }
}
