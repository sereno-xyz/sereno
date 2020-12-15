;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.confirm]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.http-form]
   [app.ui.monitors.ssl-form]
   [app.ui.monitors.healthcheck-form]
   [app.ui.monitors.common :refer [monitor-options]]
   [app.ui.monitors.http :refer [http-monitor-detail]]
   [app.ui.monitors.ssl :refer [ssl-monitor-detail]]
   [app.ui.monitors.healthcheck :refer [healthcheck-monitor]]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.util.timers :as tm]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(s/def ::tags (s/coll-of ::us/string :kind set?))
(s/def ::status ::us/not-empty-string)
(s/def ::filters-form
  (s/keys :opt-un [::tags ::status]))

(mf/defc tags-input
  {::mf/wrap-props false
   ::mf/wrap [#(mf/deferred % tm/raf)]}
  [props]
  (let [options   (obj/get props "options")
        on-change (obj/get props "on-change")
        value-fn  #(js-obj "label" % "value" %)
        options   (into-array (map value-fn options))
        value     (obj/get props "value")
        value     (into-array (map value-fn value))

        on-change*
        (mf/use-callback
         (fn [item]
           (let [value (into #{} (map #(obj/get % "value")) (seq item))]
             (on-change value))))]

    [:div.form-field
     [:> fm/creatable-select
      {:options options
       :defaultInputValue ""
       :className "react-select"
       :classNamePrefix "react-select"
       :isMulti true
       :onChange on-change*
       :value value}]]))


(mf/defc header-filters
  [{:keys [filters] :as props}]
  (let [tags  (mf/use-state (:tags filters))

        update-status
        (mf/use-callback
         (mf/deps filters)
         (fn [val]
           (let [filters (update filters :status
                                 (fn [lval]
                                   (cond
                                     (= lval val) "all"
                                     (not= lval val) val
                                     :else lval)))]
             (st/emit! (r/nav' :monitors {} filters)))))

        update-tags
        (mf/use-callback
         (mf/deps filters)
         (fn [tags]
           (let [filters (assoc filters :tags tags)]
             (st/emit! (r/nav' :monitors {} filters)))))]

    (mf/use-effect
     #(->> (rp/qry! :retrieve-all-tags {})
           (rx/subs (fn [v] (swap! tags into v)))))

    [:div.monitor-filters
     [:div.search
      [:& tags-input
       {:options @tags
        :value (:tags filters)
        :on-change update-tags}]]

     [:div.status-filter
      [:span.label "FILTER:"]
      [:ul
       [:li {:class (dom/classnames :active (= "up" (:status filters)))
             :on-click (partial update-status "up")}
        [:input {:type "checkbox"
                 :read-only true
                 :checked (= "up" (:status filters))}]
        [:label "Up"]]
       [:li {:class (dom/classnames :active (= "down" (:status filters)))
             :on-click (partial update-status "down")}
        [:input {:type "checkbox"
                 :read-only true
                 :checked (= "down" (:status filters))}]
        [:label "Down"]]]]]))

(mf/defc header
  [{:keys [filters] :as props}]
  (let [create-http-monitor
        (mf/use-callback
         (st/emitf (modal/show {:type :http-monitor-form})))

        create-ssl-monitor
        (mf/use-callback
         (st/emitf (modal/show {:type :ssl-monitor-form})))

        create-hc-monitor
        (mf/use-callback
         (st/emitf (modal/show {:type :healthcheck-monitor-form})))

        show-dropdown? (mf/use-state false)
        show-dropdown  (mf/use-callback #(reset! show-dropdown? true))
        hide-dropdown  (mf/use-callback #(reset! show-dropdown? false))]

    [:div.options-bar
     [:& header-filters {:filters filters}]
     [:a.add-button {:title "Add monitor"
                     :on-click show-dropdown} i/plus
      [:& dropdown {:show @show-dropdown?
                    :on-close hide-dropdown}
       [:ul.dropdown
        [:li {:on-click create-http-monitor
              :title "HTTP & HTTPS monitor."}
         [:div.icon i/cloud]
         [:div.text "HTTP"]]
        [:li {:on-click create-ssl-monitor
              :title "SSL certificate monitor."}
         [:div.icon i/shield-alt]
         [:div.text "SSL Cert"]]
        [:li.disabled {:title "DNS registry monitor (not implemented yet)."}
         [:div.icon i/globe]
         [:div.text "DNS"]]
        [:li {:title "Heartbeat / Keepalive monitor (not implemented yet)."
              :on-click create-hc-monitor}
         [:div.icon i/heartbeat]
         [:div.text "Keepalive"]]]]]]))

(mf/defc monitor-item
  [{:keys [item] :as props}]
  (let [status (:status item)
        router (mf/deref st/router-ref)
        uri    (str "#" (r/resolve router :monitor {:id (:id item)}))

        on-hover
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (let [target (dom/get-target event)]
             (.setAttribute target "title" (dt/timeago (:modified-at item))))))

        show-dropdown? (mf/use-state false)
        show-dropdown  (mf/use-callback #(reset! show-dropdown? true))

        show-dropdown
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (reset! show-dropdown? true)))

        hide-dropdown  (mf/use-callback #(reset! show-dropdown? false))]

    [:li.row {:key (:id item)
              :class (dom/classnames
                      :warning (= "warn" status)
                      :inactive (= "paused" status)
                      :inactive (= "started" status)
                      :success (= "up" status)
                      :failed  (= "down" status)
                      :nodata  (nil? status))}
     [:a {:href uri}
      [:div.monitor-status
       {:title (str (str/upper (:type item)) ": "
                    (case status
                      "up" "Everything is ok"
                      "down"  "The service seems down"
                      "Waiting data"))}

       (case (:type item)
         "http"        i/cloud
         "ssl"         i/shield-alt
         "healthcheck" i/heartbeat
         nil)]

      [:div.monitor-title
       [:span (:name item)]

       (when (seq (:tags item))
         (let [tags (apply str (interpose ", " (:tags item)))]
           [:span.tags {:title tags} tags]))]

      [:div.monitor-updated
       {:on-mouse-enter on-hover
        :title (dt/timeago (:modified-at item))}
       (if-let [ma (:monitored-at item)]
         (dt/format ma :datetime-med)
         "---")]

      [:div.monitor-options {:on-click show-dropdown}
       i/ellipsis-v
       [:& dropdown {:show @show-dropdown?
                     :on-close hide-dropdown}

        [:& monitor-options {:monitor item}]]]]]))

(defn- apply-filters
  [filters monitors]
  (when monitors
    (cond->> (vals monitors)
      (not (empty? (:tags filters)))
      (filter (fn [item]
                (seq (set/intersection (:tags item) (:tags filters)))))

      (not= "all" (:status filters "all"))
      (filter #(= (:status filters) (:status %)))

      true (vec))))

(mf/defc monitor-list
  [{:keys [filters] :as props}]
  (let [monitors (->> (mf/deref st/monitors-ref)
                      (apply-filters filters)
                      (sort-by :created-at))]
    [:div.monitor-list
     (cond
       (nil? monitors)
       [:div.monitors-empty
        [:h3 "Loading..."]]

       (empty? monitors)
       [:div.monitors-empty
        [:h3 "No monitors found."]]

       (not (empty? monitors))
       [:div.table
        [:div.table-body
         (for [item monitors]
           [:& monitor-item {:key (:id item) :item item}])]])]))

(mf/defc monitors-page
  {::mf/wrap [mf/memo]}
  [{:keys [params] :as props}]
  (mf/use-effect
   (fn []
     (st/emit! (ptk/event :initialize-monitor-list))
     (st/emitf (ptk/event :finalize-monitor-list))))

  [:main.monitor-list-section
   [:div.single-column-1200
    [:& header {:filters params}]
    [:& monitor-list {:filters params}]]])

(defn monitor-ref
  [id]
  #(l/derived (l/in [:monitors id]) st/state))

(mf/defc monitor-page
  {::mf/wrap [mf/memo]}
  [{:keys [id section] :as props}]

  (mf/use-effect
   (mf/deps id)
   (fn []
     (st/emit! (ptk/event :init-monitor-page {:id id}))
     (st/emitf (ptk/event :stop-monitor-page {:id id}))))

  (let [monitor-ref (mf/use-memo (mf/deps id) (monitor-ref id))
        monitor     (mf/deref monitor-ref)]
    (prn "monitor-page" monitor)
    (when monitor
      (case (:type monitor)
        "http"        [:& http-monitor-detail {:monitor monitor}]
        "ssl"         [:& ssl-monitor-detail {:monitor monitor}]
        "healthcheck" [:& healthcheck-monitor {:monitor monitor}]
        nil))))


