pipeline {
    agent any

    environment {
		SONARQUBE_SERVER = 'sonar' 
        SONAR_HOST_URL = 'http://10.153.43.8:9000'
        SCANNER_HOME = tool 'sonar-scanner' 
        SONAR_PROJECT_KEY = 'dashboard-service'
        SONAR_PROJECT_NAME = 'dashboard-service'
        IMAGE_NAME = "dashboard-service"
        CONTAINER_NAME = "dashboard-service"
         
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/gobinda1990/gst-return-3b-analysis.git'
            }
        }
         stage('Build JAR with Maven') {
            steps {
                script {
                    sh 'mvn clean package -DskipTests'
                }
            }
        }
         stage('SonarQube Analysis') {
            steps {
                echo "Running SonarQube analysis..."
                withSonarQubeEnv("${SONARQUBE_SERVER}") {
                    withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {                      
                        sh '''
                            echo "SCANNER_HOME = $SCANNER_HOME"
                            ls -l ${SCANNER_HOME}/bin/

                            ${SCANNER_HOME}/bin/sonar-scanner \
                              -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                              -Dsonar.projectName=${SONAR_PROJECT_NAME} \
                              -Dsonar.sources=src \
                              -Dsonar.java.binaries=target \
                              -Dsonar.host.url=$SONAR_HOST_URL \
                              -Dsonar.login=$SONAR_TOKEN
                        '''
                    }
                }
            }
        }     

        stage('Build Docker Image') {
            steps {
                script {
                    sh 'docker build -t ${IMAGE_NAME}:latest .'
                }
            }
        }

        stage('Stop & Remove Old Container') {
            steps {
                script {
                    sh '''
                        docker stop ${CONTAINER_NAME} || true
                        docker rm ${CONTAINER_NAME} || true
                    '''
                }
            }
        }

        stage('Run New Container') {
            steps {
                script {
                    sh 'docker run -d -p 8085:8085 --name ${CONTAINER_NAME} ${IMAGE_NAME}:latest'
                }
            }
        }
    }

    post {
        success {
            echo "Java application deployed successfully on local Docker!"
        }
        failure {
            echo "Deployment failed. Check Jenkins logs."
        }
    }
}