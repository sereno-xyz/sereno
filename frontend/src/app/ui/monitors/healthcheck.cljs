;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.healthcheck
  (:require
   ["./healthcheck_chart" :as ilc]
   [app.config :as cfg]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.forms :as forms]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.common :refer [monitor-title]]
   [app.ui.monitors.status-history :refer [monitor-brief-history]]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [promesa.core :as p]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(defn detail-ref
  [{:keys [id] :as monitor}]
  #(l/derived (l/in [:monitor-detail id]) st/state))

(mf/defc monitor-detail
  [{:keys [monitor]}]
  (let [detail-ref  (mf/use-memo (mf/deps monitor) (detail-ref monitor))
        detail      (mf/deref detail-ref)

        pause       (st/emitf (ev/pause-monitor monitor))
        resume      (st/emitf (ev/resume-monitor monitor))
        edit        #(modal/show! {::modal/type :monitor-form
                                   :item monitor})

        router      (mf/deref st/router-ref)
        log-url     (r/href router :monitor-log {:id (:id monitor)})

        on-hover
        (mf/use-callback
         (mf/deps monitor)
         (fn [event]
           (-> (dom/get-target event)
               (dom/set-attr! "title" (dt/timeago (:modified-at monitor))))))]

    ;; Fetch Summary Data
    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (st/emit! (ptk/event :init-monitor-detail-section monitor))
       (st/emitf (ptk/event :stop-monitor-detail-section monitor))))

    [:div.details-table
     [:div.details-column
      [:div.details-row
       [:div.details-field "Status"]
       [:div.details-field
        {:class (dom/classnames :success (= "up" (:status monitor))
                                :fail    (= "down" (:status monitor)))}
        (str/upper (:status monitor))]]
      [:div.details-row
       [:div.details-field "Type"]
       [:div.details-field (str/upper (:type monitor))]]
      [:div.details-row
       [:div.details-field "Cadence"]
       [:div.details-field (dt/humanize-duration (* 1000 (:cadence monitor)))]]

      [:div.details-row
       [:div.details-field "Tags"]
       [:div.details-field (apply str (interpose ", " (:tags monitor)))]]
      ]

     [:div.details-column

      [:div.details-row
       [:div.details-field "Last ping"]
       [:div.details-field
        {:on-mouse-enter on-hover}
        (dt/format (:monitored-at monitor) :datetime-med)]]

      [:div.details-row
       [:div.details-field "Log entries"]
       [:div.details-field (:total detail)]]

      [:div.details-row
       [:div.details-field "Incidents"]
       [:div.details-field (:incidents detail)]]

      [:div.details-row
       [:div.details-field ""]
       [:div.details-field
        [:a.visible-link {:href log-url} "Show all logs"]]]
      ]]))

(mf/defc help-modal
  {::mf/wrap [mf/memo]
   ::mf/register modal/components
   ::mf/register-as :healthcheck-help}
  [{:keys [monitor]}]
  (let [cancel-fn  (st/emitf (modal/hide))
        ref        (mf/use-ref)
        url        (str cfg/public-uri "/hc/" (:id monitor))]

    (mf/use-effect
     #(ex/ignoring
       (let [node (mf/ref-val ref)]
         (doseq [item (.querySelectorAll ^js node "pre code")]
           (js/hljs.highlightBlock item)))))

    [:div.modal-overlay {:ref ref}
     [:div.modal.healthcheck-help
      [:div.floating-close
       {:on-click cancel-fn}
       i/times]
      [:div.modal-content
       [:div.help-section
        [:h3 "How to ping:"]
        [:p "Keep this check up by making HTTP requests to this URL with curl: "]
        [:pre
         [:code.lang-bash
          (str "curl -m 5 --retry 5 " url)]]
        [:p "or wget:"]
        [:pre
         [:code.lang-bash
          (str "wget " url " -T 5 -t 5 -O /dev/null")]]
        ]
       [:div.help-section
        [:h3 "Signaling failures:"]
        [:p
         "You can explicitly signal failure by sending the exit code or a " [:code "fail"] " label "
         "to your normal ping URL:"]
        [:pre
         [:code.lang-bash
          "# Reports failure by appending the /fail suffix:\n"
          "curl --retry 5 " url "/fail\n\n"
          "# Reports failure by appending a non-zero exit status:\n"
          "curl --retry 5 " url "/1"]]]

       [:div.help-section
        [:h3 "Attaching logs:"]
        [:p
         "Using the POST request, you can include in the body an arbitrary text payload to be attached "
         "as metadata:"]
        [:pre
         [:code.lang-bash
          "#!/usr/bin/env sh\n"
          "logs=$(/usr/bin/some-command 2>&1)\n"
          "curl -m 5 --retry 5 --data-raw \"$logs\" " url "/$?"]]
        [:p
         "The payload size is determined by the maximum HTTP post request body size accepted by "
         "the server (default 1MB)."]]
       ]]]))

(mf/defc monitor-info
  [{:keys [monitor] :as props}]
  (let [url (str cfg/public-uri "/hc/" (:id monitor))

        copied
        (mf/use-callback
         (st/emitf (em/show {:type :success :content "copied URL" :timeout 800})))

        copy-link
        (mf/use-callback
         (mf/deps monitor)
         (fn []
           (p/then (dom/write-to-clipboard url) copied)))

        show-help
        (mf/use-callback
         (mf/deps monitor)
         (st/emitf (modal/show {:type :healthcheck-help
                                :monitor monitor})))]

    [:*
     [:div.section-title "How to ping?"]
     [:div.monitor-info
      [:p "Keep this monitor up by making HTTP requests to this URL: "]
      [:code (str cfg/public-uri "/hc/" (:id monitor))]
      [:div.buttons
       [:a.visible-link {:on-click copy-link} "Copy link"]
       [:a.visible-link {:on-click show-help} "Show examples"]]]]))


(mf/defc monitor-chart
  [{:keys [monitor] :as props}]
  (let [ref       (mf/use-ref)


        buckets   (mf/use-state [])
        bucket    (mf/use-state nil)

        on-mouse-over
        (mf/use-callback
         (mf/deps @buckets)
         (fn [index]
           (reset! bucket (nth @buckets index))))

        on-mouse-out
        (mf/use-callback
         (mf/deps @buckets)
         (fn []
           (reset! bucket nil)))]

    (mf/use-effect
     (mf/deps monitor)
     (fn []
       (->> (rp/qry! :retrieve-monitor-log-buckets {:id (:id monitor)})
            (rx/subs #(reset! buckets %)))))

    ;; Render Chart
    (mf/use-layout-effect
     (mf/deps @buckets)
     (fn []
       (when @buckets
         (let [dom  (mf/ref-val ref)
               data (clj->js @buckets)]
           (ilc/render dom #js {:width 1160
                                :height 90
                                :onMouseOver on-mouse-over
                                :onMouseOut on-mouse-out
                                :data data})
           (fn []
             (ilc/clear dom))))))

    [:*
     [:div.topside-options
      (let [data (deref bucket)]
        [:ul.period-info
         [:li
          [:span.label "Entries: "]
          [:span.value.latency  (if data (:num-entries data)  "---")]]
         [:li
          [:span.label "Period: "]
          [:span.value.period
           (if data
             (dt/format (:ts data) :date-med-with-weekday)
             "---")]]])]

     [:div.latency-chart
      [:div.chart {:ref ref}]]]))


(mf/defc healthcheck-monitor
  {::mf/wrap [mf/memo]}
  [{:keys [monitor] :as props}]
  [:*
   [:div.main-section
    [:& monitor-chart {:monitor monitor}]
    [:& monitor-detail {:monitor monitor}]]

   [:div.main-section.centered
    [:& monitor-info {:monitor monitor}]]

   [:div.main-section
    [:div.section-title "Status History"]
    [:hr]
    [:& monitor-brief-history {:monitor monitor}]]])

