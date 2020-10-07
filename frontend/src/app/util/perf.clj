;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.perf
  "Performance profiling for react components.")

(defmacro with-measure
  [name & body]
  `(let [start# (app.util.perf/timestamp)
         res#   (do ~@body)
         end#   (app.util.perf/timestamp)]
     (app.util.perf/register-measure ~name (- end# start#))
     res#))

