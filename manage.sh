#!/usr/bin/env bash
set -e

REV=`git log -n 1 --pretty=format:%h -- docker/`
DEVENV_IMGNAME="sereno-devenv"

function build-devenv {
    echo "Building development image $DEVENV_IMGNAME:latest with UID $EXTERNAL_UID..."
    local EXTERNAL_UID=${1:-$(id -u)}
    docker-compose -p serenodev -f docker/devenv/docker-compose.yaml build --force-rm --build-arg EXTERNAL_UID=$EXTERNAL_UID
}

function build-devenv-if-not-exists {
    if [[ ! $(docker images $DEVENV_IMGNAME:latest -q) ]]; then
        build-devenv $@
    fi
}

function start-devenv {
    build-devenv-if-not-exists $@;
    docker-compose -p serenodev -f docker/devenv/docker-compose.yaml up -d;
}

function stop-devenv {
    docker-compose -p serenodev -f docker/devenv/docker-compose.yaml stop -t 2;
}

function drop-devenv {
    docker-compose -p serenodev -f docker/devenv/docker-compose.yaml down -t 2 -v;

    echo "Clean old development image $DEVENV_IMGNAME..."
    docker images $DEVENV_IMGNAME -q | awk '{print $3}' | xargs --no-run-if-empty docker rmi
}

function run-devenv {
    if [[ ! $(docker ps -f "name=sereno-devenv-main" -q) ]]; then
        start-devenv
    fi

    docker exec -ti sereno-devenv-main /home/start-tmux.sh
}

function build {
    build-devenv-if-not-exists;
    local IMAGE=$DEVENV_IMGNAME:latest;

    docker volume create serenodev_user_data;

    echo "Running development image $IMAGE to build frontend."
    docker run -t --rm \
           --mount source=serenodev_user_data,type=volume,target=/home/sereno \
           --mount source=`pwd`,type=bind,target=/home/sereno/sereno \
           -w /home/sereno/sereno/$1 \
           $IMAGE ./scripts/build.sh
}

function build-frontend {
    build "frontend";
}

function build-backend {
    build "backend";
}

function build-bundle {
    build "frontend";
    build "backend";

    rm -rf ./bundle
    mkdir -p ./bundle/resources

    rsync -av ./backend/target/dist/ ./bundle/
    rsync -av ./frontend/target/dist/ ./bundle/resources/public/;

    find ./bundle/resources -iname '*.map' |xargs rm;
    rm -rf ./bundle/resources/public/fonts;
    rm -rf ./bundle/resources/public/fontawesome/svgs;
    rm -rf ./bundle/resources/public/fontawesome/sprites/regular.svg;

    NAME="sereno-$(date '+%Y.%m.%d-%H%M')"

    pushd bundle/
    tar -cvf ../$NAME.tar *;
    popd

    xz -vez4f -T4 $NAME.tar

    echo "##############################################################";
    echo "# Generated $NAME.tar.xz";
    echo "##############################################################";
}

function log-devenv {
    docker-compose -p serenodev -f docker/devenv/docker-compose.yaml logs -f --tail=50
}

function build-prodenv {
    local BUNDLE_FILE=$1;
    if [ ! -f $BUNDLE_FILE ]; then
        echo "File '$BUNDLE_FILE' does not exists.";
        exit 1;
    fi

    if [ "$BUNDLE_FILE" = "" ]; then
        echo "Missing <file> parameter."
        exit 2;
    fi

    local BUNDLE_FILE_PATH=`readlink -f $BUNDLE_FILE`;
    echo "Building prodenv with bundle: $BUNDLE_FILE_PATH."

    rm -rf ./docker/prodenv/bundle;
    mkdir -p ./docker/prodenv/bundle;

    pushd ./docker/prodenv/bundle;
    tar xvf $BUNDLE_FILE_PATH;
    popd

    pushd ./docker/prodenv;
    docker-compose -p sereno-prodenv -f ./docker-compose.yml build
    popd
}

function start-prodenv {
    pushd ./docker/prodenv;
    docker-compose -p sereno-prodenv -f ./docker-compose.yml up
    popd
}

function usage {
    echo "SERENO build & release manager v$REV"
    echo "USAGE: $0 OPTION"
    echo "Options:"
    echo "- build-devenv    Build docker development oriented image; (can specify external user id in parameter)"
    echo "- start-devenv    Start the development oriented docker-compose service."
    echo "- stop-devenv     Stops the development oriented docker-compose service."
    echo "- drop-devenv     Remove the development oriented docker-compose containers, volumes and clean images."
    echo "- run-devenv      Attaches to the running devenv container and starts development environment"
    echo "                  based on tmux (frontend at localhost:3449, backend at localhost:6060)."
    echo "- build-prodenv   [NO DOC]"
    echo "- start-prodenv   [NO DOC]"
    echo ""
}

case $1 in
    ## devenv related commands
    build-devenv)
        build-devenv ${@:2}
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
    log-devenv)
        log-devenv ${@:2}
        ;;

    # production builds
    build-frontend)
        build-frontend
        ;;

    build-backend)
        build-backend
        ;;

    build-bundle)
        build-bundle
        ;;

    # Prod Env
    start-prodenv)
        start-prodenv
        ;;

    build-prodenv)
        build-prodenv ${@:2}
        ;;

    *)
        usage
        ;;
esac
