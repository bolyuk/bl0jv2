pipeline {
    agent any
    tools {
        jdk 'GraalVM-21'
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
