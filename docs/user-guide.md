[adoptopenjdk]: https://adoptopenjdk.net/
[releases]: https://github.com/sereno-xyz/sereno/releases


# User Guide #

This document intends to explain how to start using **sereno** on a
self-hosted environment and explain the final-user neccessary details
and considerations.


## Getting Started ##

The application is built on the JVM (using Clojure & ClojureScript)
but on the end you as user of this application you only need two
dependencies:

- JDK11 or greater (JDK15 preferred)
- PostgreSQL 13 or 12 (probably can work with 11 but don't tested)

Internally I use the [JDK15 provided by adoptopenjdk][adoptopenjdk].

You can obtain the production ready bundly in two ways: building it
from source or downloading it from the [Github Releases
Page][releases]. In both cases, you will obtain a tar.xz file with all
dependencies included so no more network is required for downloading
any additional dependencies.

To build from source, you firt need to create the devenv docker image:

```bash
./manage.sh build-devenv
```

Once the image is build, you no longer need to rebuilt it until the
devenv image is changed and this happens I make some structural
changes or upgrading some dependencies.

Once we have the devenv image, let's build a new bundle:

```bash
./manage.sh build-bundle
```

This command finally will leave in the current directory a tar file
like `sereno-2020.09.09-1343.tar.xz`.

**You can skip this steps just downloading the bundle from the [Github
Releases Page][releases].**

Now having the bundle, let's proceed to build the docker images:

```bash
./manage.sh build-prodenv ./sereno-2020.09.09-1343.tar.xz
```

And finally, start it using the following command:

```bash
./manage.sh start-prodenv
```

**NOTE:** Executing the application from the bundle, serves all
requests using Jetty9 (api, and static files such as icons, js and
css); for small or medium size installations is more than enough but
for more advanced deployment is recommended serving the static files
using nginx or similar.

**NOTE:** The bundle application is not responsible of serving the
content under HTTPS, if you want to expose it to other users on your
organization consider using a HTTPS proxy (nginx or other).

**NOTE:** The bundle is platform agnostic, and it tested under
**x86_64** and **aarch64**.


## Options ##

TODO


## Backups ##

This application does not generates static files, so you only need to
backup the database. As I said previously, I use barman for properly
handle PostgreSQL backups. But for **small personal-use installations**
a simple periodict database dump should be more than enough.
