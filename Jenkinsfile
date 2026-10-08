// =============================================================================
// Udapay Ledger Service — declarative CI/CD pipeline
//
//   Build -> Test -> Security Scan -> Container Build -> Deploy -> Smoke Test
//
// The Security Scan stage is a hard gate: OWASP Dependency-Check is configured
// in backend/pom.xml with failBuildOnCVSS=7, so any High/Critical CVE fails
// the build and nothing after it runs. There is deliberately no `|| true`,
// no `-fn`, and no catchError around it.
// =============================================================================
pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        REGISTRY           = 'registry.udapay.example'
        IMAGE_NAME         = 'udapay/ledger-service'
        IMAGE_TAG          = "${env.BUILD_NUMBER}"
        DOCKER_COMPOSE_DIR = 'infra'
        MAVEN_OPTS         = '-Djava.awt.headless=true'
        // Optional: expose an NVD API key (e.g. via `withCredentials`) as NVD_API_KEY
        // to speed up the Dependency-Check CVE database download.
    }

    stages {

        stage('Build') {
            steps {
                sh 'cd backend && mvn clean package -DskipTests -q'
            }
        }

        stage('Test') {
            steps {
                sh 'cd backend && mvn test -q'
            }
            post {
                always {
                    junit allowEmptyResults: false, testResults: 'backend/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Security Scan') {
            steps {
                // Hard gate: failBuildOnCVSS=7 in pom.xml — no bypass.
                sh 'cd backend && mvn org.owasp:dependency-check-maven:check'
            }
            post {
                always {
                    archiveArtifacts artifacts: 'backend/target/dependency-check-report.*', allowEmptyArchive: true
                }
            }
        }

        stage('Container Build') {
            steps {
                sh 'cd backend && docker build -t ${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} .'
                sh 'docker tag ${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} ${REGISTRY}/${IMAGE_NAME}:latest'
            }
        }

        stage('Deploy') {
            steps {
                sh 'cd ${DOCKER_COMPOSE_DIR} && docker compose up -d --build'
            }
        }

        stage('Smoke Test') {
            steps {
                // Give the JVM time to boot (Vault + Flyway + JWK fetch), then assert health.
                sh '''
                    for i in $(seq 1 30); do
                        if curl -sf http://localhost:8080/actuator/health > /dev/null; then
                            break
                        fi
                        echo "waiting for ledger-api ($i/30) ..."
                        sleep 5
                    done
                '''
                // The gate: a non-200 health response fails the pipeline.
                sh 'curl -f http://localhost:8080/actuator/health'
            }
        }
    }

    post {
        success {
            echo "Pipeline succeeded: ${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} deployed and healthy"
        }
        failure {
            echo 'Pipeline failed — see the stage logs above (build, tests, CVE gate, or smoke test).'
        }
        always {
            archiveArtifacts artifacts: 'backend/target/*.jar', allowEmptyArchive: true
        }
    }
}
