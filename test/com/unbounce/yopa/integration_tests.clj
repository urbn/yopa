(ns com.unbounce.yopa.integration-tests
  (:require [com.unbounce.yopa.core :as yopa]
            [com.unbounce.yopa.aws-client :as aws]
            [com.unbounce.yopa.config :as config]
            [amazonica.aws.sns :as sns]
            [amazonica.aws.sqs :as sqs]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is are use-fixtures]])
  (:import com.amazonaws.AmazonServiceException))

(defn- with-yopa [f]
  (yopa/init-and-start
    (io/file "yopa-config-example.yml")
    (yopa/default-override-file))
  (f)
  (yopa/stop))

(use-fixtures :once with-yopa)

; TODO test that validates the generated aws-regions-override-test.xml file

(deftest queues-were-created
  (let [qurls (set (:queue-urls (aws/list-queues)))]
    (is (= (count qurls) 3))))

(deftest topics-were-created
  (let [tarns (set (:topics (aws/list-topics)))]
    (is (>= (count tarns) 2))))

(deftest create-topic-is-idempotent
  (let [topic-name "test-idempotency"
        tarn1 (aws/create-topic topic-name)
        tarn2 (aws/create-topic topic-name)]
    (is (= tarn1 tarn2))))

(deftest get-topic-attributes
  (let [topic-name "test-topic-without-subscription"
        tattrs (aws/get-topic-attributes (aws/make-arn "sns" topic-name))]
    (is (= (:DisplayName tattrs) topic-name))))

(deftest get-missing-topic-attributes
  (is
    (thrown-with-msg?
      AmazonServiceException
      #"No topic found for ARN: _doesnt_exist_"
      (aws/get-topic-attributes "_doesnt_exist_"))))

(deftest unsupported-sns-operation
  (is
    (thrown-with-msg?
      AmazonServiceException
      #"Unsupported action: ConfirmSubscription"
      (aws/run-on-sns #(sns/confirm-subscription "fake-arn" "fake-token")))))

(defn- receive-one-message [queue-name]
  (let [queue-url (aws/queue-name->url queue-name)
        receive-result (aws/run-on-sqs
                         #(sqs/receive-message
                            :queue-url queue-url
                            :wait-time-seconds 10
                            :max-number-of-messages 10
                            :delete true))
        messages (:messages receive-result)]
    (is (= 1 (count messages)))
    (->> messages first :body)))

(deftest topic-with-subscriptions-delivery
  (let [topic-arn (aws/make-arn "sns" "test-topic-with-subscriptions")
        subject (str "test subject " (rand-int 32768))
        message (str "test message " (rand-int 32768))
        publish-result (aws/run-on-sns
                         #(sns/publish :topic-arn topic-arn
                                       :subject subject
                                       :message message))
        message-id (:message-id publish-result)]
    (is (some? message-id))
    (let [standard-message (json/read-str
                             (receive-one-message "test-subscribed-queue-standard")
                             :key-fn keyword)
          raw-message (receive-one-message "test-subscribed-queue-raw")]
      (are [a e] (= a e)
           message raw-message
           message (:Message standard-message)
           subject (:Subject standard-message)
           "Notification" (:Type standard-message)))))

(deftest topic-without-subscription-delivery
  (let [message-id (aws/run-on-sns
                     #(sns/publish :topic-arn "arn:aws:sns:yopa-local:000000000000:test-topic-without-subscription"
                                   :subject "test subject"
                                   :message "test message"))]
    (is (some? message-id))))
