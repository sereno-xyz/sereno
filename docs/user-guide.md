[adoptopenjdk]: https://adoptopenjdk.net/
[releases]: https://github.com/sereno-xyz/sereno/releases
[dockerhub]: https://hub.docker.com/r/niwinz/sereno
[dockerhub-arm]: https://hub.docker.com/r/niwinz/sereno-arm64


# User Guide #

This document intends to explain how to start using **sereno** on a
self-hosted environment and explain the final-user neccessary details
and considerations.

The application is built on the JVM (using Clojure & ClojureScript)
but on the end you as user of this application you only need two
dependencies:

- JDK11 or greater (JDK15 preferred)
- PostgreSQL 13 or 12 (probably can work with 11 but don't tested)

Internally I use the [JDK15 provided by adoptopenjdk][adoptopenjdk].


## Getting Started with Docker ##

You have several ways to get started with sereno. The more easy step
is just using **docker** and **docker-compose**; but there are also
instructions and hints on how to deploy it in a more traditional way.

Lets start with a quick approach using docker and docker-compose; the
prebuild images are uploaded to [DockerHub][dockerhub].

Start creating a sample `docker-compose.yml` file:

```yaml
---
version: "3.5"

networks:
  default:
    driver: bridge
    ipam:
      config:
        - subnet: 172.172.4.0/24

volumes:
  postgres_data:

services:
  app:
    image: "niwinz/sereno:latest"
    hostname: "sereno-app"
    depends_on:
      - db
    environment:
      - SERENO_DATABASE_URI=postgresql://db:5432/sereno
      - SERENO_DATABASE_USERNAME=sereno
      - SERENO_DATABASE_PASSWORD=sereno
      - SERENO_SECRET_KEY=somesecretkeyhere
      - SERENO_PUBLIC_URI=http://localhost:9876
      - SERENO_SENDMAIL_BACKEND=console
    ports:
      - 9876:4460

  db:
    image: postgres:13
    hostname: "sereno-postgresql"
    restart: always
    environment:
      - POSTGRES_INITDB_ARGS=--data-checksums
      - POSTGRES_DB=sereno
      - POSTGRES_USER=sereno
      - POSTGRES_PASSWORD=sereno
    volumes:
      - postgres_data:/var/lib/postgresql/data
```

Then execute:

```bash
docker-compose -p sereno -f docker-compose.yml pull
```

And finally start all containers with:

```bash
docker-compose -p sereno -f docker-compose.yml up -d
```

The application will listen on http://localhost:9876/

__**Relevant considerations**__:

- The example configuration uses __console__ sendmail backend that is
  usefull for experimenting with sereno but not very usefull if you
  want get notified. Refer to **options** section on how to properly
  configure email sending.
- Executing the application from the bundle, serves all requests using
  Jetty9 (api, and static files such as icons, js and css); for small
  or medium size installations is more than enough but for more
  advanced deployment is recommended serving the static files using
  nginx or similar.
- The docker image is not responsible of serving the content
  under HTTPS, if you want to expose it to other users on your
  organization consider using a HTTPS proxy (nginx or other).
- If you want to deploy it on ARM board's (like RPI4), there are also
  images for ARM64 in [DockerHub][dockerhub-arm].


## Hints for traditional deploy ##

If you don't like docker or just prefer a not container based deploy,
you just can use the production bundle (an archive that contains all
sereno application and the dependencies).

You have two ways to obtain the production bundle: downloading it from
[Github Releases][releases] or build it from source. Building if from
source has the advantage that you can build the latest ongoing
version.

You just need to upload and uncompress the bundle to a VPS with
openjdk installed; and create the startup script. In case you use a
distribution with systemd, this can be a good candidate:

```ini
[Unit]
Description=Sereno Backend Application

[Service]
WorkingDirectory=/var/www/app
ExecStart=/bin/bash run.sh
User=www-data
Group=www-data
KillMode=mixed
KillSignal=SIGTERM
TimeoutStopSec=5s
Type=simple
Restart=on-failure
RestartSec=10

Environment=SERENO_PUBLIC_URI=https://yourdomain
Environment=SERENO_DATABSE_URI=postgres://dbhostname:port/dbname
Environment=SERENO_DATABASE_USERNAME=sereno
Environment=SERENO_DATABASE_PASSWORD=sereno
Environment=SERENO_SECRET_KEY=somesecretkeyhere
Environment=SERENO_SENDMAIL_BACKEND=console


[Install]
WantedBy=multi-user.target
```

The `/var/www/app` is the directory when the bundle is uploaded and
uncompressed.


**NOTE:** The bundle archive is platform agnostic, and it tested under
**amd64** and **arm64** (AWS Graviton2).


## Configuration ##

This section is a reference to the configuration variables that
_sereno_ accepts. All configuration variables are defined in terms of
Environment variables.


### Security ###

```bash
# Key used for authenticate webhooks
SERENO_WEBHOOK_SHARED_KEY=some-random-key

# Key used for generatin tokens;
SERENO_SECRET_KEY=some-other-random-key
```


### Database ###

Database related options:

```bash
SERENO_DATABASE_URI=postgresql://localhost:5432/sereno

# Optional
SERENO_DATABASE_USERNAME=sereno

# Optional
SERENO_DATABASE_PASSWORD=somepassword
```

### Email

Email sending options:

```bash
# Sets the sending backend; available options: smtp, console
SERENO_SENDMAIL_BACKEND=smtp

# The default From, mandatory, should be a valid email
SERENO_SMTP_DEFAULT_FROM=no-reply@yourdomain.com

# The default ReplyTo, mandatory, should be a valid email
SERENO_SMTP_DEFAULT_REPLY_TO=no-reply@yourdomain.com

# Smtp credentials; all optional
SERENO_SMTP_PASSWORD=<password>
SERENO_SMTP_USERNAME=<username>
SERENO_SMTP_HOST=<host>
SERENO_SMTP_PORT=<port>
SERENO_SMTP_TLS=true
```

### Google Auth ###


```bash
SERENO_GOOGLE_CLIENT_ID=<google-client-id>
SERENO_GOOGLE_CLIENT_SECRET=<google-client-secret>

# Without this options, authentication with google will not work.
```

You also should set the authorized callback on gauth console to:

```text
https://<yourdomain>/api/oauth/google/callback
```


### Telegram ###

Telgram integration, optional:

```bash
SERENO_TELEGRAM_TOKEN=<token-of-your-telegram-bot>
SERENO_TELEGRAM_ID=<id-of-your-telegram-bot-user>
```

The telegram integration will be deactivated if this two options not
provided. The webhook for telegram is <yourdomain>/webhook/telegram?secret_key=<webhook-secret-key>


### Other ###

```bash
# Options to pass to the JVM; Optional (example values openjdk15)
JVM_OPTS="-XX:+AlwaysPreTouch -Xms128m -Xmx128m -XX:+UseZGC"

# Options to pass to the JVM; Optional (example values)
JVM_OPTS="-XX:+AlwaysPreTouch -Xms128m -Xmx128m -XX:+UnlockExperimentalVMOptions -XX:+UseZGC"
```


## Other advanced topics ##

### Build bundle from source ###

To build from source, you first need to create the devenv docker
image:

```bash
./manage.sh build-devenv
```

Once the image is build, you no longer need to rebuilt it until the
devenv image is changed and this happens I make some structural
changes or upgrading some dependencies.

Once we have the devenv image, let's build a new bundle:

```bash
./manage.sh build-bundle latest
```
This command finally will leave in the current directory a tar file
like `latest.tar.xz`.


### Build docker images ###

Now having the bundle, let's proceed to build the docker images:

```bash
./manage.sh build-image ./bundle.tar.xz
```

This will generate the `localhost/sereno:latest` image that you can
use in a docker-compose setup in the same way as explained on the
_Getting started_ section.


### Backups ###

This application does not generates static files, so you only need to
backup the database. As I said previously, I use barman for properly
handle PostgreSQL backups. But for **small personal-use installations**
a simple periodict database dump should be more than enough.
