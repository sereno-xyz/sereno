FROM debian:buster
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG DEBIAN_FRONTEND=noninteractive

ENV NODE_VERSION=v15.4.0 \
    CLOJURE_VERSION=1.10.1.754 \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

RUN set -ex; \
    mkdir -p /etc/resolvconf/resolv.conf.d; \
    echo "nameserver 8.8.8.8" > /etc/resolvconf/resolv.conf.d/tail; \
    apt-get -qq update; \
    apt-get -qqy install --no-install-recommends \
        locales \
        gnupg2 \
        ca-certificates \
        wget \
        sudo \
        tmux \
        procps \
        vim \
        curl \
        bash \
        git \
        rlwrap \
        bzip2 \
        nginx \
        jq \
        unzip \
        xz-utils \
    ; \
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen; \
    locale-gen; \
    rm -rf /var/lib/apt/lists/*;

RUN set -ex; \
    wget -qO - https://adoptopenjdk.jfrog.io/adoptopenjdk/api/gpg/key/public | apt-key add -; \
    echo "deb https://adoptopenjdk.jfrog.io/adoptopenjdk/deb/ buster main" >> /etc/apt/sources.list.d/adoptopenjdk.list; \
    apt-get -qq update; \
    apt-get -qqy install adoptopenjdk-15-hotspot; \
    rm -rf /var/lib/apt/lists/*; \
    wget "https://download.clojure.org/install/linux-install-$CLOJURE_VERSION.sh"; \
    chmod +x "linux-install-$CLOJURE_VERSION.sh"; \
    "./linux-install-$CLOJURE_VERSION.sh"; \
    rm -rf "linux-install-$CLOJURE_VERSION.sh"

RUN set -ex; \
    curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -; \
    echo "deb http://apt.postgresql.org/pub/repos/apt buster-pgdg main" >> /etc/apt/sources.list.d/postgresql.list; \
    apt-get -qq update; \
    apt-get -qqy install postgresql-client-13; \
    rm -rf /var/lib/apt/lists/*;

COPY files/phantomjs-mock /usr/bin/phantomjs
COPY files/bashrc         /root/.bashrc
COPY files/vimrc          /root/.vimrc
COPY files/tmux.conf      /root/.tmux.conf


RUN set -ex; \
    useradd -m -g users -s /bin/bash sereno; \
    passwd sereno -d; \
    echo "sereno ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers;


WORKDIR /home

RUN set -ex; \
    mkdir -p /tmp/node; \
    cd /tmp/node; \
    export PATH="$PATH:/usr/local/nodejs/bin"; \
    wget https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-x64.tar.xz; \
    tar xvf node-$NODE_VERSION-linux-x64.tar.xz; \
    mv /tmp/node/node-$NODE_VERSION-linux-x64 /usr/local/nodejs; \
    chown -R root /usr/local/nodejs; \
    /usr/local/nodejs/bin/npm install -g yarn; \
    rm -rf /tmp/node;

RUN set -ex; \
    cd /tmp; \
    wget https://github.com/borkdude/clj-kondo/releases/download/v2020.11.07/clj-kondo-2020.11.07-linux-amd64.zip; \
    unzip clj-kondo-2020.11.07-linux-amd64.zip; \
    mv clj-kondo /usr/local/bin/; \
    rm clj-kondo-2020.11.07-linux-amd64.zip;

EXPOSE 4448
EXPOSE 4449
EXPOSE 4460
EXPOSE 4490

COPY files/start-tmux.sh  /home/start-tmux.sh
COPY files/entrypoint.sh  /home/entrypoint.sh
COPY files/init.sh        /home/init.sh
COPY files/bashrc         /home/sereno/.bashrc

ENTRYPOINT ["bash", "/home/entrypoint.sh"]
CMD ["/home/init.sh"]
