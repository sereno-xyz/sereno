const fs = require("fs");
const path = require("path");
const l = require("lodash");

const CleanCSS = require("clean-css");
const gulp = require("gulp");
const gulpif = require("gulp-if");
const gzip = require("gulp-gzip");

const mustache = require("gulp-mustache");
const rename = require("gulp-rename");
const svgSprite = require("gulp-svg-sprite");

const mkdirp = require("mkdirp");
const rimraf = require("rimraf");
const sass = require("sass");
const autoprefixer = require("autoprefixer")
const postcss = require("postcss")

const mapStream = require("map-stream");

const paths = {};

paths.resources = "./resources/";
paths.output = "./resources/public/";
paths.dist = "./target/dist/";
paths.scss = "./resources/styles/**/*.scss";

/***********************************************
 * Helpers
 ***********************************************/

function isProduction() {
  return (process.env.NODE_ENV === "production");
}

function scssPipeline(options) {
  const write = (_path, data) => {
    return new Promise((resolve, reject) => {
      fs.writeFile(_path, data, function(err) {
        if (err) { reject(err); }
        else { resolve(); }
      });
    });
  };

  const touch = (_path) => {
    return new Promise((resolve, reject) => {
      return fs.utimes(_path, new Date(), new Date(), () => {
        resolve(_path);
      });
    })
  }

  const render = (input) => {
    return new Promise((resolve, reject) => {
      sass.render({file: input}, async function(err, result) {
        if (err) {
          console.log(err.formatted);
          reject(err);
        } else {
          resolve(result.css);
        }
      });
    });
  };

  const postprocess = (data, input, output) => {
    return postcss([autoprefixer])
      .process(data, {map: false, from: input, to: output})
  };

  const clean = (data) => {
    if (isProduction()) {
      const cleaner = new CleanCSS({
        format: "keep-breaks",
        level: 1
      });
      const result = cleaner.minify(data);

      if (result.errors.length > 0) {
        console.log(result.errors); // a list of errors raised
      }

      if (result.warnings.length > 0) {
        console.log(result.warnings); // a list of warnings raised
      }

      return result.styles;
    } else {
      return data;
    }
  }

  return function(next) {
    const input = options.input;
    const output = options.output;

    return mkdirp(path.dirname(output))
      .then(() => render(input))
      .then((res) => postprocess(res, input, output))
      .then(async (res) => {
        const css = clean(res.css)
        await write(output, css);
        await touch(output);
        return res;
      })
      .catch((err) => {
        console.log(err);
      })
      .then(() => {
        next();
      });
  };
}

// Templates

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

gulp.task("scss:main", scssPipeline({
  input: paths.resources + "styles/main.scss",
  output: paths.output + "css/main.css"
}));

gulp.task("scss", gulp.parallel("scss:main"));

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
  gulp.watch(paths.scss, gulp.series("scss"));
  gulp.watch([paths.resources + "templates/*.mustache",
              paths.resources + "locales.json"],
             gulp.series("template:main"));
});

gulp.task("build", gulp.series("scss", "templates", "copy:assets"));

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

gulp.task("dist:gzip", function() {
  return gulp.src(`${paths.dist}**/!(*.gz|*.br|*.jpg|*.png)`)
    .pipe(gzip({gzipOptions: {level: 9}}))
    .pipe(gulp.dest(paths.dist));
});
