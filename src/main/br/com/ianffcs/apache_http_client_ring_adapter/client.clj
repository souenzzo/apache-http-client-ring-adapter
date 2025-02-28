(ns br.com.ianffcs.apache-http-client-ring-adapter.client
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [ring.core.protocols])
  (:import (org.apache.http.client.methods CloseableHttpResponse
                                    HttpRequestBase
                                    HttpEntityEnclosingRequestBase)
           (org.apache.http HeaderIterator
                            StatusLine
                            HttpEntity
                            Header)
           (org.apache.http.impl.client CloseableHttpClient)
           (org.eclipse.jetty.http HttpStatus)
           (java.io ByteArrayOutputStream)
           (java.net UnknownHostException ConnectException)))

(set! *warn-on-reflection* true)

(defn format-headers [^HttpRequestBase request]
  (transduce (map (fn [^Header header]
                    [(string/lower-case (.getName header))
                     (.getValue header)]))
             (fn
               ([] {})
               ([result] result)
               ([result [k v]]
                (if (contains? result k)
                  (update result k str "," v)
                  (assoc result k v))))
             (.getAllHeaders request)))

(defn ->http-client
  [ring-handler]
  (proxy [CloseableHttpClient] []
    (doExecute [_target ^HttpRequestBase request _context]
      (let [method                        (keyword (string/lower-case (.getMethod request)))
            uri                           (.getURI request)
            {:keys [port host path query scheme]} (bean uri)
            headers                       (format-headers request)
            req-map                       {:request-method method
                                           :uri            path
                                           :server-port    port
                                           :query-string   query
                                           :server-name    host
                                           :remote-addr    "127.0.0.1"
                                           :protocol       (str (.getProtocolVersion request))
                                           :scheme         (keyword scheme)
                                           :headers        headers}
            {:keys [headers status body]
             :as response}                (ring-handler
                                           (cond-> req-map
                                             (instance? HttpEntityEnclosingRequestBase request)
                                             (assoc :body
                                                    (some-> ^HttpEntityEnclosingRequestBase request
                                                            .getEntity
                                                            .getContent))))
            response-baos (ByteArrayOutputStream.)
            _ (ring.core.protocols/write-body-to-stream body response response-baos)
            body-as-bytes (.toByteArray response-baos)]
        (reify CloseableHttpResponse
          (close [_this])
          (getEntity [_this]
            (reify HttpEntity
              (getContent [_this]
                (io/input-stream body-as-bytes))
              (getContentLength [_this]
                (count body-as-bytes))
              (isRepeatable [_this]
                false)
              (isStreaming [_this]
                false)
              (isChunked [_this]
                false)))

          (getStatusLine [_this]
            (reify StatusLine
              (getReasonPhrase [_this] (HttpStatus/getMessage status))
              (getStatusCode [_this] status)
              (getProtocolVersion [_this]
                (.getProtocolVersion request))))

          (headerIterator [_this]
            (let [headers (atom (for [[k vs] headers
                                      v (if (coll? vs)
                                          vs
                                          [vs])]
                                  (reify Header
                                    (getName [_this] k)
                                    (getValue [_this] (str v)))))]
              (reify HeaderIterator
                (hasNext [_this] (not (empty? @headers)))
                (next [_this]
                  (ffirst (swap-vals! headers rest)))))))))))

(defn unknown-host-exception
  "When can't resolve the host in DNS"
  [{:keys [server-name]}]
  (UnknownHostException. server-name))


(defn offline-exception
  "When can't resolve the name because it's offline"
  [{:keys [server-name]}]
  (UnknownHostException. (str server-name ": Temporary failure in name resolution")))

(defn connect-exception
  "When java can't open a socket in the address"
  [_]
  (ConnectException. "Connection refused"))


#_(comment
    (-> '[clj-http.client :refer [request]]
        (require '[clojure.test :refer [deftest is testing]]))

    (deftest ->http-client-test
      (testing "simple get"
        (let [mocked-client (->http-client (fn [req]
                                             {:body    "ian"
                                              :headers {"hello" "world"}
                                              :status  202}))]
          (is (= {:cached                nil,
                  :request-time          1,
                  :repeatable?           false,
                  :protocol-version      {:name "HTTP", :major 1, :minor 1},
                  :streaming?            false,
                  :http-client           mocked-client
                  :chunked?              false,
                  :reason-phrase         "Accepted",
                  :headers               {"hello" "world"},
                  :orig-content-encoding nil,
                  :status                202,
                  :length                3,
                  :body                  "ian",
                  :trace-redirects       []}
                 (request {:url         "https://souenzzo.com.br/foo/bar"
                           :method      :get
                           :http-client mocked-client})))))
      (testing "simple get"
        (let [mocked-client (->http-client (fn [req]
                                             (def _r req)
                                             {:body    "ian"
                                              :headers {"hello" "world"}
                                              :status  202}))]
          (is (= {:cached                nil,
                  :request-time          1,
                  :repeatable?           false,
                  :protocol-version      {:name "HTTP", :major 1, :minor 1},
                  :streaming?            false,
                  :http-client           mocked-client
                  :chunked?              false,
                  :reason-phrase         "Accepted",
                  :headers               {"hello" "world"},
                  :orig-content-encoding nil,
                  :status                202,
                  :length                3,
                  :body                  "ian",
                  :trace-redirects       []}
                 (request {:url         "https://souenzzo.com.br/foo/bar"
                           :method      :post
                           :body        {}
                           :http-client mocked-client})))))))



#_#_{:keys [authority port host scheme]} (-> url URI/create bean)
