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
    }
}
