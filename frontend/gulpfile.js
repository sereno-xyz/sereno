const fs = require("fs");
const path = require("path");
const l = require("lodash");
const gulp = require("gulp");
const mustache = require("gulp-mustache");
const rename = require("gulp-rename");
const svgSprite = require("gulp-svg-sprite");
const concat = require("gulp-concat");
const mkdirp = require("mkdirp");
const rimraf = require("rimraf");
const mapStream = require("map-stream");
const paths = {};

paths.resources = "./resources/";
paths.output = "./resources/public/";
paths.dist = "./target/dist/";
paths.styles = "./resources/styles/**/*.css";

/***********************************************
 * Helpers
 ***********************************************/

function readLocales() {
  const path = __dirname + "/resources/locales.json";
  const content = JSON.parse(fs.readFileSync(path, {encoding: "utf8"}));

  let result = {};
  for (let key of Object.keys(content)) {
    const item = content[key];
    if (l.isString(item)) {
      result[key] = {"en": item};
    } else if (l.isPlainObject(item) && l.isPlainObject(item.translations)) {
      result[key] = item.translations;
    }
  }

  return JSON.stringify(result);
}

function readManifest() {
  try {
    const path = __dirname + "/resources/public/js/manifest.json";
    const content = JSON.parse(fs.readFileSync(path, {encoding: "utf8"}));
    const now = Date.now();

    const index = {
      "config": "/js/config.js?ts=" + now,
      "locales": "/js/locales.js?ts=" + now
    };

    for (let item of content) {
      index[item.name] = "/js/" + item["output-name"] + "?ts=" + now;
    };

    return index;
  } catch (e) {
    console.error("Error on reading manifest, using default.");
    return {
      "main": "/js/main.js",
      "config": "/js/config.js",
      "locales": "/js/locales.js"
    };
  }
}

function touch() {
  return mapStream(function(file, cb) {
    if (file.isNull()) {
      return cb(null, file);
    }

    // Update file modification and access time
    return fs.utimes(file.path, new Date(), new Date(), () => {
      cb(null, file)
    });
  });
}

function templatePipeline(options) {
  return function() {
    const input = options.input;
    const output = options.output;
    const ts = Math.floor(new Date());
    const locales = readLocales();
    const manifest = readManifest();

    const defaultConf = [
      "var appPublicURI = null;",
      "var appDeployDate = null;",
      "var appDeployCommit = null;"
    ];

    fs.writeFileSync(__dirname + "/resources/public/js/config.js",
                     defaultConf.join("\n"));

    fs.writeFileSync(__dirname + "/resources/public/js/locales.js",
                     `var appTranslations = JSON.parse(${JSON.stringify(locales)});`);

    const tmpl = mustache({
      ts: ts,
      manifest: manifest,
      translations: JSON.stringify(locales)
    });

    return gulp.src(input)
      .pipe(tmpl)
      .pipe(rename("index.html"))
      .pipe(gulp.dest(output))
      .pipe(touch());
  };
}

/***********************************************
 * Generic
 ***********************************************/

gulp.task("css:main", function() {
  const autoprefixer = require("autoprefixer");
  const postcss      = require('gulp-postcss');
  const sourcemaps   = require('gulp-sourcemaps');
  const neested      = require("postcss-nested");
  const clean        = require("postcss-clean");

  return gulp.src("resources/styles/main/*.css")
    .pipe(sourcemaps.init())
    .pipe(postcss([
      neested,
      autoprefixer,
      clean({format: "keep-breaks", level: 1})
    ]))
    .pipe(concat("main.css"))
    .pipe(sourcemaps.write('.'))
    .pipe(gulp.dest("resources/public/css/"));
});

gulp.task("css", gulp.parallel("css:main"));

gulp.task("svg:sprite", function() {
  return gulp.src(paths.resources + "images/icons/*.svg")
    .pipe(rename({prefix: "icon-"}))
    .pipe(svgSprite({
      mode: {symbol: {inline: true}}
      // svg: {xmlDeclaration: false}
    }))
    .pipe(gulp.dest(paths.resources + "images/sprites/"));
});

gulp.task("template:main", templatePipeline({
  input: paths.resources + "templates/index.mustache",
  output: paths.output
}));

gulp.task("templates", gulp.series("svg:sprite", "template:main"));

/***********************************************
 * Development
 ***********************************************/

gulp.task("clean", function(next) {
  rimraf(paths.output, next);
});

gulp.task("copy:assets:images", function() {
  return gulp.src(paths.resources + "images/**/*")
    .pipe(gulp.dest(paths.output + "images/"));
});

gulp.task("copy:assets:fonts", function() {
  return gulp.src(paths.resources + "fonts/**/*")
    .pipe(gulp.dest(paths.output + "fonts/"));
});

gulp.task("copy:assets", gulp.parallel("copy:assets:fonts",
                                       "copy:assets:images"));

gulp.task("dev:dirs", function(next) {
  mkdirp("./resources/public/css/").then(() => next())
});

gulp.task("watch:main", function() {
  gulp.watch(paths.styles, gulp.series("css"));
  gulp.watch([paths.resources + "templates/*.mustache",
              paths.resources + "locales.json"],
             gulp.series("template:main"));
});

gulp.task("build", gulp.series("css", "templates", "copy:assets"));

gulp.task("watch", gulp.series(
  "dev:dirs",
  "build",
  "watch:main"
));

/***********************************************
 * Production
 ***********************************************/

gulp.task("dist:clean", function(next) {
  rimraf(paths.dist, next);
});

gulp.task("dist:copy", function() {
  return gulp.src(paths.output + "**/*")
    .pipe(gulp.dest(paths.dist));
});
