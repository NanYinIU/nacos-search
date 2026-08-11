// Nacos Search CI — self-hosted Jenkins on beijing (jenkins.gorsen.icu)
//
// Constraints (validated 2026-08-03):
// - Host ~3.6G RAM + 3G swapfile (/swapfile-ci). Jenkins cgroup 1.5G.
// - Heavy Gradle/IDE work runs in *sibling* Docker containers (host docker.sock),
//   not inside the Jenkins cgroup.
// - Docker -v mounts MUST use host path /opt/jenkins/home/... (never $WORKSPACE alone).
// - IntelliJ plugin tests need ~2500m container + capped Test JVM heap
//   (gradle-caches/init.d/ci-heap.init.gradle). 1.2–2.0G OOMs with exit 137.
// - githubPush() registers only after the first manual build.
// - Branch is master (not main).
// - Prefer Tencent Gradle mirror; services.gradle.org times out from this host.

pipeline {
    agent any

    triggers {
        githubPush()
    }

    options {
        disableConcurrentBuilds()
        timeout(time: 120, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '15'))
        timestamps()
    }

    environment {
        // Agent shell: /var/jenkins_home ; sibling docker -v: /opt/jenkins/home
        HOST_WORKSPACE     = "/opt/jenkins/home/workspace/${JOB_BASE_NAME}"
        HOST_GRADLE_CACHE  = '/opt/jenkins/home/gradle-caches'
        AGENT_GRADLE_CACHE = '/var/jenkins_home/gradle-caches'
        BUILD_IMAGE        = 'eclipse-temurin:17-jdk'
        // Proven floor for full test suite on this host (with /swapfile-ci)
        BUILD_MEM          = '2500m'
        BUILD_MEMSWAP      = '3500m'
        GRADLE_OPTS        = '-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8'
    }

    stages {
        stage('Checkout') {
            steps {
                sh '''
                    set -euo pipefail
                    git config --global http.version HTTP/1.1
                    git config --global http.postBuffer 524288000
                    git config --global http.lowSpeedLimit 1000
                    git config --global http.lowSpeedTime 600
                '''
                // GitHub ↔ Tencent Cloud is flaky (HTTP/2 cancel / early EOF); retry + 60min clone timeout
                retry(5) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/master']],
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'CloneOption', depth: 1, shallow: true, noTags: true, timeout: 60],
                        ],
                        userRemoteConfigs: [[
                            url: 'https://github.com/NanYinIU/nacos-search.git',
                            credentialsId: 'jenkins-github-app',
                        ]],
                    ])
                }
                sh '''
                    set -euo pipefail
                    mkdir -p "${AGENT_GRADLE_CACHE}/init.d"
                    # Cap Test JVMs so IntelliJ platform tests fit BUILD_MEM (see host docs).
                    # Drop any older init scripts that may still pin a collector — a stale
                    # -XX:+UseSerialGC next to IntelliJ's -XX:+UseG1GC aborts every
                    # Gradle Test Executor ("Conflicting collector combinations").
                    rm -f "${AGENT_GRADLE_CACHE}/init.d/"*.gradle
                    cat > "${AGENT_GRADLE_CACHE}/init.d/ci-heap.init.gradle" <<'EOF'
allprojects {
    tasks.withType(Test).configureEach {
        maxHeapSize = "1024m"
        minHeapSize = "256m"
        jvmArgs("-XX:MaxMetaspaceSize=384m")
    }
}
// Belt-and-suspenders: strip collector selects from direct jvmArgs even if
// another init script re-adds SerialGC. IntelliJ's G1 stays on ArgumentProviders.
gradle.taskGraph.whenReady {
    def collector = ~/^(-XX:[+-]Use\w+GC)$/
    allprojects {
        tasks.withType(Test).each { t ->
            def args = t.jvmArgs
            if (args != null) {
                t.jvmArgs = args.findAll { !(it ==~ collector) }
            }
        }
    }
}
EOF
                    chmod -R u+rwX "${WORKSPACE}" "${AGENT_GRADLE_CACHE}" 2>/dev/null || true
                '''
            }
        }

        stage('Unit tests') {
            steps {
                sh '''
                    set -euo pipefail
                    docker pull "${BUILD_IMAGE}"
                    # Free ~300MB for the heavy test container (restarted in post)
                    docker stop linkding 2>/dev/null || true
                    docker run --rm \
                        --name "nacos-search-unit-${BUILD_NUMBER}" \
                        --memory="${BUILD_MEM}" \
                        --memory-swap="${BUILD_MEMSWAP}" \
                        -u 1000:1000 \
                        -v "${HOST_WORKSPACE}:/work" \
                        -v "${HOST_GRADLE_CACHE}:/gradle-home" \
                        -e GRADLE_USER_HOME=/gradle-home \
                        -e GRADLE_OPTS="${GRADLE_OPTS}" \
                        -e HOME=/gradle-home \
                        -e CI=true \
                        -e JENKINS_URL="${JENKINS_URL:-https://jenkins.gorsen.icu/}" \
                        -w /work \
                        "${BUILD_IMAGE}" \
                        bash -lc '
                            set -euo pipefail
                            # beijing → services.gradle.org is flaky
                            sed -i "s|services.gradle.org/distributions|mirrors.cloud.tencent.com/gradle|g" gradle/wrapper/gradle-wrapper.properties
                            sed -i "s|networkTimeout=[0-9]*|networkTimeout=300000|g" gradle/wrapper/gradle-wrapper.properties
                            cat gradle/wrapper/gradle-wrapper.properties
                            ./gradlew compileKotlin compileTestKotlin --no-daemon --no-watch-fs
                            # instrumentCode races under parallel; testVintage isolates JUnit4 ApplicationRule;
                            # --no-configuration-cache: testVintage mirrors IntelliJ classpath at config time.
                            ./gradlew test testVintage --no-parallel --no-configuration-cache --no-daemon --no-watch-fs
                        '
                '''
            }
        }

        stage('Live smoke V1') {
            steps {
                sh '''
                    set -euo pipefail
                    NET="nacos-search-ci-${BUILD_NUMBER}"
                    NACOS_NAME="nacos-ci-v1-${BUILD_NUMBER}"
                    BUILD_NAME="nacos-search-v1-${BUILD_NUMBER}"

                    cleanup() {
                        docker rm -f "${BUILD_NAME}" >/dev/null 2>&1 || true
                        docker rm -f "${NACOS_NAME}" >/dev/null 2>&1 || true
                        docker network rm "${NET}" >/dev/null 2>&1 || true
                    }
                    trap cleanup EXIT

                    docker network create "${NET}"
                    docker pull nacos/nacos-server:v2.5.3

                    # CentOS 7 + overlayfs: Derby pwrite fails without seccomp=unconfined
                    docker run -d \
                        --name "${NACOS_NAME}" \
                        --network "${NET}" \
                        --security-opt seccomp=unconfined \
                        --memory=512m \
                        --memory-swap=512m \
                        -e MODE=standalone \
                        -e JVM_XMS=256m -e JVM_XMX=256m -e JVM_XMN=64m \
                        nacos/nacos-server:v2.5.3

                    echo "Waiting for Nacos 2.5.3..."
                    ok=0
                    for i in $(seq 1 60); do
                        if docker run --rm --network "${NET}" curlimages/curl:8.5.0 \
                            -sf "http://${NACOS_NAME}:8848/nacos/v1/console/namespaces" >/dev/null 2>&1; then
                            ok=1
                            break
                        fi
                        echo "  attempt ${i}/60..."
                        sleep 3
                    done
                    if [ "${ok}" != "1" ]; then
                        docker logs "${NACOS_NAME}" 2>&1 | tail -80
                        exit 1
                    fi

                    docker run --rm \
                        --name "${BUILD_NAME}" \
                        --network "${NET}" \
                        --memory="${BUILD_MEM}" \
                        --memory-swap="${BUILD_MEMSWAP}" \
                        -u 1000:1000 \
                        -v "${HOST_WORKSPACE}:/work" \
                        -v "${HOST_GRADLE_CACHE}:/gradle-home" \
                        -e GRADLE_USER_HOME=/gradle-home \
                        -e GRADLE_OPTS="${GRADLE_OPTS}" \
                        -e HOME=/gradle-home \
                        -e NACOS_LIVE_V1_ENDPOINT="http://${NACOS_NAME}:8848" \
                        -w /work \
                        "${BUILD_IMAGE}" \
                        bash -lc '
                            set -euo pipefail
                            sed -i "s|services.gradle.org/distributions|mirrors.cloud.tencent.com/gradle|g" gradle/wrapper/gradle-wrapper.properties
                            sed -i "s|networkTimeout=[0-9]*|networkTimeout=300000|g" gradle/wrapper/gradle-wrapper.properties
                            ./gradlew test --no-parallel --no-configuration-cache --no-daemon --no-watch-fs \
                                --tests "com.nanyin.nacos.search.services.operations.LiveSmokeTest.V1*"
                        '
                '''
            }
        }

        stage('Live smoke V3') {
            steps {
                sh '''
                    set -euo pipefail
                    NET="nacos-search-ci-${BUILD_NUMBER}"
                    NACOS_NAME="nacos-ci-v3-${BUILD_NUMBER}"
                    BUILD_NAME="nacos-search-v3-${BUILD_NUMBER}"

                    cleanup() {
                        docker rm -f "${BUILD_NAME}" >/dev/null 2>&1 || true
                        docker rm -f "${NACOS_NAME}" >/dev/null 2>&1 || true
                        docker network rm "${NET}" >/dev/null 2>&1 || true
                    }
                    trap cleanup EXIT

                    docker network create "${NET}"
                    docker pull nacos/nacos-server:v3.2.3

                    # V3 needs ~1G on this host (512m can hang at "starting"; 768m flaky under load)
                    docker run -d \
                        --name "${NACOS_NAME}" \
                        --network "${NET}" \
                        --security-opt seccomp=unconfined \
                        --memory=1024m \
                        --memory-swap=1024m \
                        -e MODE=standalone \
                        -e JVM_XMS=512m -e JVM_XMX=512m -e JVM_XMN=128m \
                        -e NACOS_AUTH_ENABLE=false \
                        -e NACOS_AUTH_ADMIN_ENABLE=false \
                        -e NACOS_AUTH_TOKEN=SecretKey012345678901234567890123456789012345678901234567890123456789 \
                        -e NACOS_AUTH_IDENTITY_KEY=serverIdentity \
                        -e NACOS_AUTH_IDENTITY_VALUE=security \
                        nacos/nacos-server:v3.2.3

                    echo "Waiting for Nacos 3.2.3..."
                    ok=0
                    for i in $(seq 1 72); do
                        if docker run --rm --network "${NET}" curlimages/curl:8.5.0 \
                            -sf "http://${NACOS_NAME}:8848/nacos/v3/admin/core/state" >/dev/null 2>&1 \
                          && docker run --rm --network "${NET}" curlimages/curl:8.5.0 \
                            -sf "http://${NACOS_NAME}:8848/nacos/v3/admin/cs/config/list?pageNo=1&pageSize=1&dataId=&group=&search=accurate&namespaceId=public" >/dev/null 2>&1; then
                            ok=1
                            break
                        fi
                        echo "  attempt ${i}/72..."
                        sleep 5
                    done
                    if [ "${ok}" != "1" ]; then
                        docker logs "${NACOS_NAME}" 2>&1 | tail -100
                        exit 1
                    fi

                    docker run --rm \
                        --name "${BUILD_NAME}" \
                        --network "${NET}" \
                        --memory="${BUILD_MEM}" \
                        --memory-swap="${BUILD_MEMSWAP}" \
                        -u 1000:1000 \
                        -v "${HOST_WORKSPACE}:/work" \
                        -v "${HOST_GRADLE_CACHE}:/gradle-home" \
                        -e GRADLE_USER_HOME=/gradle-home \
                        -e GRADLE_OPTS="${GRADLE_OPTS}" \
                        -e HOME=/gradle-home \
                        -e NACOS_LIVE_V3_ENDPOINT="http://${NACOS_NAME}:8848" \
                        -w /work \
                        "${BUILD_IMAGE}" \
                        bash -lc '
                            set -euo pipefail
                            sed -i "s|services.gradle.org/distributions|mirrors.cloud.tencent.com/gradle|g" gradle/wrapper/gradle-wrapper.properties
                            sed -i "s|networkTimeout=[0-9]*|networkTimeout=300000|g" gradle/wrapper/gradle-wrapper.properties
                            ./gradlew test --no-parallel --no-configuration-cache --no-daemon --no-watch-fs \
                                --tests "com.nanyin.nacos.search.services.operations.LiveSmokeTest.V3*"
                        '
                '''
            }
        }
    }

    post {
        always {
            sh '''
                docker start linkding 2>/dev/null || true
                docker ps -aq --filter "name=nacos-search-" --filter "name=nacos-ci-" | xargs -r docker rm -f >/dev/null 2>&1 || true
                docker network ls --format "{{.Name}}" | grep -E "^nacos-search-ci-" | xargs -r docker network rm >/dev/null 2>&1 || true
            '''
        }
        success {
            echo "nacos-search CI green (unit + live V1 + live V3)"
        }
        failure {
            echo "nacos-search CI failed — see stage logs; need /swapfile-ci + ~2.5G build container"
        }
    }
}
