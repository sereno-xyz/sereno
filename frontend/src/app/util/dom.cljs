;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.dom
  (:require
   [goog.dom :as dom]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [app.util.transit :as ts]))

;; --- Deprecated methods

(defn event->inner-text
  [e]
  (.-innerText (.-target e)))

(defn event->value
  [e]
  (.-value (.-target e)))

(defn event->target
  [e]
  (.-target e))

(defn classnames
  [& params]
  (assert (even? (count params)))
  (str/join " " (reduce (fn [acc [k v]]
                          (if (true? (boolean v))
                            (conj acc (name k))
                            acc))
                        []
                        (partition 2 params))))

;; --- New methods

(defn get-element-by-class
  ([classname]
   (dom/getElementByClass classname))
  ([classname node]
   (dom/getElementByClass classname node)))

(defn get-element
  [id]
  (dom/getElement id))

(defn stop-propagation
  [e]
  (when e
    (.stopPropagation e)))

(defn prevent-default
  [e]
  (when e
    (.preventDefault e)))

(defn get-target
  "Extract the target from event instance."
  [event]
  (.-target ^js event))

(defn get-current-target
  "Extract the target from event instance."
  [event]
  (.-currentTarget ^js event))

(defn get-parent
  [dom]
  (.-parentElement ^js dom))

(defn get-value
  "Extract the value from dom node."
  [node]
  (.-value node))

(defn click
  "Click a node"
  [node]
  (.click node))

(defn get-files
  "Extract the files from dom node."
  [node]
  (.-files node))

(defn checked?
  "Check if the node that represents a radio
  or checkbox is checked or not."
  [node]
  (.-checked node))

(defn valid?
  "Check if the node that is a form input
  has a valid value, against html5 form validation
  properties (required, min/max, pattern...)."
  [node]
  (.-valid (.-validity node)))

(defn clean-value!
  [node]
  (set! (.-value node) ""))

(defn select-text!
  [node]
  (.select node))

(defn ^boolean equals?
  [node-a node-b]
  (.isEqualNode node-a node-b))

(defn get-event-files
  "Extract the files from event instance."
  [event]
  (get-files (get-target event)))

(defn create-element
  ([tag]
   (.createElement js/document tag))
  ([ns tag]
   (.createElementNS js/document ns tag)))

(defn set-attr!
  [el attr val]
  (.setAttribute ^js el attr val)
  el)

(defn set-html!
  [el html]
  (set! (.-innerHTML el) html))

(defn get-data-attr!
  [node n]
  (when-let [ds (.-dataset ^js node)]
    (unchecked-get ds n)))

(defn append-child!
  [el child]
  (.appendChild el child))

(defn get-first-child
  [el]
  (.-firstChild el))

(defn get-tag-name
  [el]
  (.-tagName el))

(defn get-outer-html
  [el]
  (.-outerHTML el))

(defn get-inner-text
  [el]
  (.-innerText el))

(defn query
  [el query]
  (.querySelector el query))

(defn get-client-position
  [event]
  (let [x (.-clientX event)
        y (.-clientY event)]
    {:x x :y y}))

(defn get-client-size
  [node]
  {:width (.-clientWidth ^js node)
   :height (.-clientHeight ^js node)})

(defn focus!
  [node]
  (.focus node))

(defn fullscreen?
  []
  (boolean (.-fullscreenElement js/document)))

(defn ^boolean blob?
  [v]
  (instance? js/Blob v))

(defn create-blob
  "Create a blob from content."
  ([content]
   (create-blob content "application/octet-stream"))
  ([content mimetype]
   (js/Blob. #js [content] #js {:type mimetype})))

(defn revoke-uri
  [url]
  (js/URL.revokeObjectURL url))

(defn create-uri
  "Create a url from blob."
  [b]
  {:pre [(blob? b)]}
  (js/URL.createObjectURL b))

(defn write-to-clipboard
  [data]
  (assert (string? data) "`data` should be string")
  (let [cboard (unchecked-get js/navigator "clipboard")]
    (.writeText ^js cboard data)))
