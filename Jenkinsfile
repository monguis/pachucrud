// Runs on a push to any branch: tests and builds the image, pushes it to the
// local registry and points staging at it in pachuco-ci-cd, which Argo CD
// syncs. Prod is promoted by hand in values-prod.yaml.
//
// The pushed branch is in env.BRANCH.
//
// Credentials:
//   github-local  username + PAT with push access to monguis/pachuco-ci-cd

pipeline {
    agent any

    options {
        disableConcurrentBuilds()
    }

    tools {
        // This tells Jenkins to download and inject the Docker CLI into the PATH for this build
        dockerTool 'latest'
    }

    environment {
        IMAGE = 'localhost:5123/pachucrud'
        CI_CD_REPO = 'github.com/monguis/pachuco-ci-cd.git'
        CI_CD_VALUES = 'values-staging.yaml'
    }

    stages {
        stage('Tag') {
            steps {
                // The protos the build compiles live in the pachuco-proto
                // submodule, which the checkout leaves empty. It's public, so
                // no credentials.
                sh 'git submodule update --init --depth 1'
                script {
                    // gets commit tag
                    env.TAG = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    // Multibranch jobs set BRANCH_NAME; a plain Pipeline job
                    // triggered by a push only has GIT_BRANCH (origin/<branch>).
                    env.BRANCH = env.BRANCH_NAME ?: env.GIT_BRANCH?.replaceFirst('^origin/', '')
                    if (!env.BRANCH) {
                        error 'Could not tell which branch was pushed'
                    }
                    echo "Building ${env.BRANCH} at ${env.TAG}"
                }
            }
        }

        stage('Test') {
            steps {
                // Plain docker run, so the agent needs no JDK and Jenkins no
                // Docker Pipeline plugin. Jenkins runs in a container and
                // drives the host's Docker, so the workspace path only exists
                // in Jenkins' own volume: share it with --volumes-from instead
                // of a bind mount. Runs as the Jenkins user so the files it
                // leaves in the workspace stay deletable; the container's HOME
                // isn't writable by that user, hence the Maven home in the
                // workspace.
                //
                // The integration tests start Postgres and Redis with
                // Testcontainers: --volumes-from also brings the Docker socket,
                // --group-add lets the Jenkins user use it, and the containers
                // they start are siblings on the host, reached through
                // host.docker.internal rather than localhost.
                sh '''
                    docker run --rm -u "$(id -u):$(id -g)" \
                        --group-add "$(stat -c %g /var/run/docker.sock)" \
                        --volumes-from "$HOSTNAME" -w "$WORKSPACE" \
                        -e HOME="$WORKSPACE/.home" \
                        -e MAVEN_CONFIG="$WORKSPACE/.home/.m2" \
                        -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
                        maven:3.9-eclipse-temurin-17 \
                        mvn -B -Duser.home="$WORKSPACE/.home" verify
                '''
            }
        }

        stage('Build') {
            steps {
                sh 'docker build -t "pachucrud:$TAG" .'
            }
        }

        stage('Push') {
            steps {
                sh '''
                    docker tag "pachucrud:$TAG" "$IMAGE:$TAG"
                    docker push "$IMAGE:$TAG"
                '''
            }
        }

        stage('Deploying') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'github-local', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
                    sh '''
                        rm -rf ci-cd
                        git clone --depth 1 "https://$GIT_USER:$GIT_TOKEN@$CI_CD_REPO" ci-cd
                        cd ci-cd
                        git config user.name Jenkins
                        git config user.email jenkins@pachuco.local

                        # sed, not yq: yq reflows the whole file. Only touches the
                        # tag line inside the top-level pachucrud: block.
                        sed -i "/^pachucrud:/,/^[^ ]/ s/^    tag: .*/    tag: \\"$TAG\\"/" "$CI_CD_VALUES"
                        grep -q "^    tag: \\"$TAG\\"" "$CI_CD_VALUES"

                        # A rebuild of the same commit leaves nothing to deploy.
                        if git diff --quiet; then
                            echo "$CI_CD_VALUES already at $TAG"
                            exit 0
                        fi

                        git commit -am "pachucrud: $TAG ($BRANCH)"
                        # Another branch or app may have pushed in between.
                        for attempt in 1 2 3; do
                            git push origin HEAD:main && exit 0
                            git pull --rebase origin main
                        done
                        exit 1
                    '''
                }
            }
        }
    }

    post {
        always {
            sh 'docker rmi "pachucrud:$TAG" "$IMAGE:$TAG" 2>/dev/null || true'
            // The clone's remote URL holds the token.
            sh 'rm -rf ci-cd'
        }
    }
}
