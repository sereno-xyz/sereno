# Developer Guide #

This document intends to explain how to get the development
environment up and running with some additional tips.

The default development environment uses docker and docker-compose
but you are free to use any other options including running it
directly on bare-metal.


Internally I call the development environment as **devenv** and it
consists on some scripts and docker-compose.


## System requirements ##

You should have `docker` and `docker-compose` installed in your system
in order to set up properly the development enviroment.

In debian like linux distributions you can install it executing:

```bash
sudo apt-get install docker docker-compose
```

Start and enable docker environment:


```bash
sudo systemctl start docker
sudo systemctl enable docker
```

Add your user to the docker group:

```basb
sudo usermod -aG docker $USER
```

And finally, increment user watches:

```
echo fs.inotify.max_user_watches=524288 | sudo tee -a /etc/sysctl.conf && sudo sysctl -p
```

NOTE: you probably need to login again for group change take the effect.


## Start the devenv ##

The devenv consists mainly on two containers: **main** which has all
the dependencies need to build and run the application (nginx, gulp,
frontend compilation, backend) and **postgresql**.

I use _tmux_ for for multuplexing a shell session inside the running
container and run many different processes, so it **requires a minimum
knowledge of tmux usage in order to use that development
environment**.

For start it, staying in this repository, execute:

```bash
./manage.sh run-devenv
```

This will do the following:

- Build the images if it is not done before.
- Starts all the containers in the background.
- Attaches to the **devenv** container and executes the tmux session.
- The tmux session automatically starts all the necessary services.

You can execute the individual steps manully if you want:

```bash
./manage.sh build-devenv # builds the devenv docker image
./manage.sh start-devenv # starts background running containers
./manage.sh run-devenv   # enters to new tmux session inside of one of the running containers
./manage.sh stop-devenv  # stops background running containers
./manage.sh drop-devenv  # removes all the volumes, containers and networks used by the devenv
```


## First steps with tmux ##

Now having the the container running and tmux open inside the
container, you are free to execute any commands and open many shells
as you want.

You can create a new shell just pressing the **Ctr+b c** shortcut. And
**Ctrl+b w** for switch between windows, **Ctrl+b &** for kill the
current window.

For more info: https://tmuxcheatsheet.com/


## Inside the tmux session ##

### gulp ###

The first tmux window is busy with gulp watch process that is
responsible of compiling sass, templates and static asstes
copying. Also it responsible of a portion of tasks used for build a
production frontend bundle.

### shadow-cljs ###

On the next window (**1**) you fill find the shadow-cljs watch
process. It is responsible on compiling the ClojureScript code.


### clojure (jvm backend) ###

And on the third tmux window you will the backend repl. By default it
just enters in REPL with everything stopped. So you will need to
interact with it in order to start the backend. This is a small
cheatsheet for the functions available on the REPL:

- `(start)`: start all the environment
- `(stop)`: stops the environment
- `(restart)`: stops, reload and start again.
- `(repl/refresh-all)` hard reload all namespaces.
