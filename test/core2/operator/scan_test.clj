(ns core2.operator.scan-test
  (:require [clojure.test :as t]
            [core2.api :as c2]
            [core2.ingester :as ingest]
            [core2.local-node :as node]
            [core2.operator :as op]
            [core2.test-util :as tu]
            [core2.util :as util])
  (:import (core2 IResultCursor)))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-simple-scan
  (with-open [node (node/start-node {})]
    (let [tx (c2/submit-tx node [[:put {:_id :foo, :col1 "foo1"}]
                                 [:put {:_id :bar, :col1 "bar1", :col2 "bar2"}]
                                 [:put {:_id :foo, :col2 "baz2"}]])
          ingester (tu/component node :core2/ingester)]
      (t/is (= [{:_id :bar, :col1 "bar1", :col2 "bar2"}]
               (op/query-ra '[:scan [_id col1 col2]]
                            (ingest/snapshot ingester tx)))))))

(t/deftest multiple-sources
  (with-open [node1 (node/start-node {})
              node2 (node/start-node {})]
    (let [tx1 (c2/submit-tx node1 [[:put {:_id :foo, :col1 "col1"}]])
          db1 (ingest/snapshot (tu/component node1 :core2/ingester) tx1)
          tx2 (c2/submit-tx node2 [[:put {:_id :foo, :col2 "col2"}]])
          db2 (ingest/snapshot (tu/component node2 :core2/ingester) tx2)]
      (t/is (= [{:_id :foo, :col1 "col1", :col2 "col2"}]
               (op/query-ra '[:join [{_id _id}]
                              [:scan $db1 [_id col1]]
                              [:scan $db2 [_id col2]]]
                            {'$db1 db1, '$db2 db2}))))))

(t/deftest test-duplicates-in-scan-1
  (with-open [node (node/start-node {})]
    (let [ingester (tu/component node :core2/ingester)
          tx (c2/submit-tx node [[:put {:_id :foo}]])]
      (t/is (= [{:_id :foo}]
               (op/query-ra '[:scan [_id _id]]
                            (ingest/snapshot ingester tx)))))))

(t/deftest test-scanning-temporal-cols
  (with-open [node (node/start-node {})]
    (let [ingester (tu/component node :core2/ingester)
          tx @(c2/submit-tx node [[:put {:_id :doc}
                                   {:_valid-time-start #inst "2021"
                                    :_valid-time-end #inst "3000"}]])]

      (let [res (first (op/query-ra '[:scan [_id
                                             _valid-time-start _valid-time-end
                                             system_time_start system_time_end]]
                                    (ingest/snapshot ingester tx)))]
        (t/is (= #{:_id :_valid-time-start :_valid-time-end :system_time_end :system_time_start}
                 (-> res keys set)))

        (t/is (= {:_id :doc, :_valid-time-start (util/->zdt #inst "2021"), :_valid-time-end (util/->zdt #inst "3000")}
                 (dissoc res :system_time_start :system_time_end))))

      (t/is (= {:_id :doc, :vt-start (util/->zdt #inst "2021"), :vt-end (util/->zdt #inst "3000")}
               (-> (first (op/query-ra '[:project [_id
                                                   {vt-start _valid-time-start}
                                                   {vt-end _valid-time-end}]
                                         [:scan [_id _valid-time-start _valid-time-end]]]
                                       (ingest/snapshot ingester tx)))
                   (dissoc :system_time_start :system_time_end)))))))

#_ ; FIXME hangs
(t/deftest test-only-scanning-temporal-cols-45
  (with-open [node (node/start-node {})]
    (let [ingester (tu/component node :core2/ingester)
          tx @(c2/submit-tx node [[:put {:_id :doc}]])]

      (t/is (op/query-ra '[:scan [_valid-time-start _valid-time-end
                                  system_time_start system_time_end]]
                         (ingest/snapshot ingester tx))))))

(t/deftest test-scan-col-types
  (with-open [node (node/start-node {})]
    (let [ingester (tu/component node :core2/ingester)]
      (letfn [(->col-types [tx]
                (let [snap (ingest/snapshot ingester tx)]
                  (with-open [^IResultCursor rs (op/open-ra '[:scan [_id]] snap {})]
                    (.columnTypes rs))))]

        (let [tx (-> (c2/submit-tx node [[:put {:_id :doc}]])
                     (tu/then-await-tx node))]
          (tu/finish-chunk node)

          (t/is (= '{_id [:extension-type :keyword :utf8 ""]}
                   (->col-types tx))))

        (let [tx (-> (c2/submit-tx node [[:put {:_id "foo"}]])
                     (tu/then-await-tx node))]

          (t/is (= '{_id [:union #{[:extension-type :keyword :utf8 ""] :utf8}]}
                   (->col-types tx))))))))
