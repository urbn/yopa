(ns com.unbounce.yopa.aws-client
  (:require [amazonica.core :as aws]
            [amazonica.aws.sqs :as sqs]
            [amazonica.aws.sns :as sns]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:dynamic config (atom nil))

(defn make-arn [service name]
  (let [region (:region @config)]
    (format "arn:aws:%s:%s:000000000000:%s" service region name)))

(defn arn->name [arn]
  (last (str/split arn #":")))

(defn queue-name->url [queue-name]
  (let [{:keys [host sqs-port]} @config]
    (format "http://%s:%d/queue/%s" host sqs-port queue-name)))

(defn queue-arn->url [queue-arn]
  (queue-name->url (arn->name queue-arn)))

(defn run-on-sqs [f]
  (let [{:keys [host sqs-port]} @config]
    (aws/with-credential ["x" "x" (str "http://" host ":" sqs-port)]
      (f))))

(defn run-on-sns [f]
  (let [{:keys [host sns-port]} @config]
    (aws/with-credential ["x" "x" (str "http://" host ":" sns-port)]
      (f))))

(defn create-queue [queue-name]
  (:queue-url (run-on-sqs #(sqs/create-queue queue-name))))

(defn list-queues []
  (run-on-sqs sqs/list-queues))

(defn create-topic [topic-name]
  (:topic-arn (run-on-sns #(sns/create-topic topic-name))))

(defn list-topics []
  (run-on-sns sns/list-topics))

(defn get-topic-attributes [topic-arn]
  (:attributes (run-on-sns #(sns/get-topic-attributes topic-arn))))

(defn subscribe [endpoint protocol topic-arn]
  (:subscription-arn
    (run-on-sns
      #(sns/subscribe
         :protocol protocol
         :topic-arn topic-arn
         :endpoint endpoint))))

(defn set-raw-delivery [subscription-arn]
  (run-on-sns
    #(sns/set-subscription-attributes
       :subscription-arn subscription-arn
       :attribute-name "RawMessageDelivery"
       :attribute-value true)))

(defn send-sqs-message [queue-arn message]
  (let [queue-url (queue-arn->url queue-arn)]
    (run-on-sqs
      #(sqs/send-message queue-url message))))

(defn init [servers-config]
  (reset! config servers-config))
