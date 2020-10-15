FROM debian:buster
LABEL maintainer="Andrey Antukh <niwi@niwi.nz>"

ARG DEBIAN_FRONTEND=noninteractive
ARG EXTERNAL_UID=1000

ENV NODE_VERSION=v14.13.0 \
    CLOJURE_VERSION=1.10.1.716 \
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
        vim \
        curl \
        bash \
        git \
        rlwrap \
        bzip2 \
        nginx \
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

COPY files/bashrc         /root/.bashrc
COPY files/vimrc          /root/.vimrc
COPY files/tmux.conf      /root/.tmux.conf
COPY files/start-tmux.sh  /home/start-tmux.sh
COPY files/entrypoint.sh  /home/entrypoint.sh
COPY files/init.sh        /home/init.sh
COPY files/phantomjs-mock /usr/bin/phantomjs

RUN set -ex; \
    useradd -m -g users -s /bin/bash -u $EXTERNAL_UID sereno; \
    passwd sereno -d; \
    echo "sereno ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers;

USER sereno
WORKDIR /home/sereno

RUN set -ex; \
    git clone https://github.com/creationix/nvm.git .nvm; \
    bash -c "source .nvm/nvm.sh && nvm install $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm alias default $NODE_VERSION"; \
    bash -c "source .nvm/nvm.sh && nvm use default"; \
    bash -c "source .nvm/nvm.sh && npm install -g yarn";

EXPOSE 4448
EXPOSE 4449
EXPOSE 4460
EXPOSE 4490

ENTRYPOINT ["bash", "/home/entrypoint.sh"]
CMD ["/home/init.sh"]
