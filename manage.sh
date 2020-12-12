#!/usr/bin/env bash
set -e

export ORGANIZATION="serenoxyz";
export DOCKER_IMAGE="$ORGANIZATION/sereno";
export DEVENV_IMGNAME="$ORGANIZATION/devenv";
export DEVENV_PNAME="serenodev";

export CURRENT_USER_ID=$(id -u);
export CURRENT_VERSION=$(git describe --tags);
export CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD);
export CURRENT_HASH=$(git rev-parse --short HEAD);

function build-devenv {
    echo "Building development image."

    pushd docker/devenv;
    set -x
    docker build -t $DEVENV_IMGNAME:latest .
    set +x
    popd;
}

function push-devenv {
    set -x;
    docker push $DEVENV_IMGNAME:latest;
}

function pull-devenv {
    set -x;
    docker pull $DEVENV_IMGNAME:latest;
}

function pull-devenv-if-not-exists {
    if [[ ! $(docker images $DEVENV_IMGNAME:latest -q) ]]; then
        pull-devenv $@
    fi
}

function start-devenv {
    pull-devenv-if-not-exists $@;
    docker-compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml up -d;
}

function stop-devenv {
    docker-compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml stop -t 2;
}

function drop-devenv {
    docker-compose -p $DEVENV_PNAME -f docker/devenv/docker-compose.yaml down -t 2 -v;
    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function run-devenv {
    if [[ ! $(docker ps -f "name=sereno-devenv-main" -q) ]]; then
        start-devenv
    fi

    docker exec -ti sereno-devenv-main sudo -EH -u sereno /home/start-tmux.sh
}

function build {
    pull-devenv-if-not-exists;
    docker volume create ${DEVENV_PNAME}_user_data;

    echo "Running development image $IMAGE to build $1."
    docker run -t --rm \
           --mount source=${DEVENV_PNAME}_user_data,type=volume,target=/home/sereno \
           --mount source=`pwd`,type=bind,target=/home/sereno/sereno \
           -w /home/sereno/sereno/$1 \
           -e EXTERNAL_UID=$CURRENT_USER_ID \
           -e SHADOWCLJS_EXTRA_PARAMS=${SHADOWCLJS_EXTRA_PARAMS} \
           $DEVENV_IMGNAME:latest sudo -EH -u sereno ./scripts/build.sh
}

function build-frontend {
    build "frontend";
}

function build-backend {
    build "backend";
}

function build-bundle {
    # build "frontend";
    # build "backend";
    set -x

    rm -rf ./bundle;
    mkdir -p ./bundle/resources;

    rsync -aq ./backend/target/dist/ ./bundle/;
    rsync -aq ./frontend/target/dist/ ./bundle/resources/public/;

    find ./bundle/resources -iname '*.map' |xargs rm;
    rm -rf ./bundle/resources/public/fonts;
    rm -rf ./bundle/resources/public/fa

    local version="$CURRENT_VERSION";
    local name="sereno-$CURRENT_VERSION";

    if [ $CURRENT_BRANCH != "main" ]; then
        local ncommits=$(git rev-list --count HEAD);
        version="$CURRENT_BRANCH-$ncommits-$CURRENT_HASH";
        name="sereno-$CURRENT_BRANCH";
    fi;

    echo $version > ./bundle/version.txt

    sed -i -re "s/\%version\%/$version/g" ./bundle/main/app/config.clj
    sed -i -re "s/\%version\%/$version/g" ./bundle/resources/public/index.html;

    local generate_tar=${SERENO_BUILD_GENERATE_TAR:-"true"};
    if [ $generate_tar == "true" ]; then
        pushd bundle/
        tar -cf ../$name.tar *;
        popd

        xz -vez1f -T4 $name.tar

        echo "##############################################################";
        echo "# Generated $name.tar.xz";
        echo "##############################################################";
    fi
}

function build-image {
    local version="$CURRENT_VERSION";
    local bundle_file="sereno-$CURRENT_VERSION";

    if [ $CURRENT_BRANCH != "main" ]; then
        version="$CURRENT_BRANCH";
        bundle_file="sereno-$CURRENT_BRANCH.tar.xz";
    fi;

    if [ ! -f $bundle_file ]; then
        echo "File '$bundle_file' does not exists.";
        exit 1;
    fi

    rm -rf ./docker/image/bundle;
    mkdir -p ./docker/image/bundle;

    local bundle_file_path=`readlink -f $bundle_file`;
    echo "Building docker image from: $bundle_file_path.";

    pushd ./docker/image/bundle;
    tar xvf $bundle_file_path;
    popd

    pushd ./docker/image;

    set -x
    docker buildx build --platform linux/amd64 -t $DOCKER_IMAGE:$version-amd64 .;
    docker buildx build --platform linux/arm64 -t $DOCKER_IMAGE:$version-arm64 .;

    popd
}

function publish-latest-image {
    if [ $CURRENT_BRANCH != "main" ]; then
        echo "Release can be published from master only."
        exit 1;
    fi;

    set -x;
    docker push $DOCKER_IMAGE:$CURRENT_VERSION-amd64;
    docker push $DOCKER_IMAGE:$CURRENT_VERSION-arm64;

    docker manifest create \
           $DOCKER_IMAGE:$CURRENT_VERSION \
           $DOCKER_IMAGE:$CURRENT_VERSION-amd64 \
           $DOCKER_IMAGE:$CURRENT_VERSION-arm64;

    docker manifest create \
           $DOCKER_IMAGE:latest \
           $DOCKER_IMAGE:$CURRENT_VERSION-amd64 \
           $DOCKER_IMAGE:$CURRENT_VERSION-arm64;

    docker manifest push $DOCKER_IMAGE:$CURRENT_VERSION;
    docker manifest push $DOCKER_IMAGE:latest;
}

function publish-snapshot-image {
    local version="$CURRENT_BRANCH";

    set -x;
    docker push $DOCKER_IMAGE:$version-amd64;
    docker push $DOCKER_IMAGE:$version-arm64;

    docker manifest create \
           $DOCKER_IMAGE:$version \
           $DOCKER_IMAGE:$version-amd64 \
           $DOCKER_IMAGE:$version-arm64;

    docker manifest push $DOCKER_IMAGE:$version;
}

function usage {
    echo "SERENO build & release manager v$REV"
    echo "USAGE: $0 OPTION"
    echo "Options:"
    echo "- build-devenv    Build docker development oriented image."
    echo "- start-devenv    Start the development oriented docker-compose service."
    echo "- stop-devenv     Stops the development oriented docker-compose service."
    echo "- drop-devenv     Drop the development oriented docker-compose containers, volumes and clean images."
    echo "- run-devenv      Attaches to the running devenv container and starts development environment"
    echo "                  based on tmux (frontend at localhost:3449, backend at localhost:6060)."
    echo "- build-bundle    [NO DOC]"
    echo "- build-image     [NO DOC]"
    echo "- publish-latest-image   [NO DOC]"
    echo "- publish-snapshot-image   [NO DOC]"
    echo ""
}

case $1 in
    ## devenv related commands
    build-devenv)
        build-devenv ${@:2}
        ;;
    pull-devenv)
        pull-devenv ${@:2}
        ;;

    push-devenv)
        push-devenv ${@:2}
        ;;
    start-devenv)
        start-devenv ${@:2}
        ;;
    run-devenv)
        run-devenv ${@:2}
        ;;
    stop-devenv)
        stop-devenv ${@:2}
        ;;
    drop-devenv)
        drop-devenv ${@:2}
        ;;

    # production builds
    build-frontend)
        build-frontend
        ;;

    build-backend)
        build-backend
        ;;

    build-bundle)
        build-bundle ${@:2}
        ;;

    build-image)
        build-image ${@:2}
        ;;

    publish-latest-image)
        publish-latest-image;
        ;;

    publish-snapshot-image)
        publish-snapshot-image;
        ;;

    *)
        usage
        ;;
esac
