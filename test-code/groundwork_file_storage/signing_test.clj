(ns groundwork-file-storage.signing-test
  "SigV4 against AWS's published test suite (aws-signing-test-suite/v4, as
   awslabs/aws-c-auth carries it): each case's request, and the
   signature it must get in the Authorization header and in the query
   string. The suite's example credentials, AKIDEXAMPLE, are AWS's own,
   published with it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-file-storage.signing :as signing])
  (:import
   (java.time Instant)))

(def ^:private credentials
  {:access-key "AKIDEXAMPLE"
   :secret-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
   :region     "us-east-1"
   :service    "service"})

(def ^:private signed-at (Instant/parse "2015-08-30T12:36:00Z"))

(def ^:private body-hash
  "The sha256 of Param1=value1, post-x-www-form-urlencoded's body."
  "9095672bbd1f56dfc5b65f3e153adc8731a4a654192329106275f4c7b24d0b6e")

(def ^:private cases
  "[name method path query headers payload-hash header-signature query-signature]:
   path as the request writes it, before encoding; headers without x-amz-date."
  [["get-vanilla" "GET" "/" [] {"Host" "example.amazonaws.com"} signing/empty-payload-hash
    "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
    "e93c787ed7f371d5c6b165c1b38ede9550f4dce4144713e844b25b7192d3865d"]
   ["get-vanilla-query-order-key-case" "GET" "/" [["Param2" "value2"] ["Param1" "value1"]] {"Host" "example.amazonaws.com"}
    signing/empty-payload-hash
    "b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500"
    "86012e2c9ad4d77369f5d81c11f75158aae4f895a085212cc6d3f923d300bed5"]
   ["get-vanilla-query-unreserved" "GET" "/"
    [["-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
      "-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"]]
    {"Host" "example.amazonaws.com"} signing/empty-payload-hash
    "9c3e54bfcdf0b19771a7f523ee5669cdf59bc7cc0884027167c21bb143a40197"
    "8e76a88a7433637b12778d5592799b29ad21ecd6cf6325051c21d86f0acda2bf"]
   ["get-vanilla-utf8-query" "GET" "/" [["ሴ" "bar"]] {"Host" "example.amazonaws.com"} signing/empty-payload-hash
    "2cdec8eed098649ff3a119c94853b13c643bcf08f8b0a1d91e12c9027818dd04"
    "0bdd809b1519ac4f0c1dc3540e2cc46bd0c7f778eda408b2ebf3b913d21ff600"]
   ["get-unreserved" "GET" "/-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" []
    {"Host" "example.amazonaws.com"} signing/empty-payload-hash
    "07ef7494c76fa4850883e2b006601f940f8a34d404d0cfa977f52a65bbf5f24f"
    "95968482db1b9e0fadef6efc1bd24689f77c77d9ef56919c96a28cc92e0d6005"]
   ["get-utf8" "GET" "/ሴ" [] {"Host" "example.amazonaws.com"} signing/empty-payload-hash
    "8318018e0b0f223aa2bbf98705b62bb787dc9c0e678f255a891fd03141be5d85"
    "10eae3f14a260bd3911cc6d008d3c576d143b05b62f09782a7a4b37f52178e44"]
   ["get-space-unnormalized" "GET" "/example space/" [] {"Host" "example.amazonaws.com"} signing/empty-payload-hash
    "652487583200325589f1fba4c7e578f72c47cb61beeca81406b39ddec1366741"
    "7a1f416954786484c9824d93c1f26ef64acb9b1b6c9154d08c9f07d0e394abf6"]
   ["get-header-value-trim" "GET" "/" []
    {"Host" "example.amazonaws.com" "My-Header1" " value1" "My-Header2" " \"a   b   c\""} signing/empty-payload-hash
    "acc3ed3afb60bb290fc8d2dd0098b9911fcaa05412b367055dee359757a9c736"
    "e7bb0fd515e125e1aec2ecc4c0c17484fb06f6846b927c35e46005dd3df3acd4"]
   ["post-header-key-sort" "POST" "/" [] {"Host" "example.amazonaws.com" "My-Header1" "value1"} signing/empty-payload-hash
    "c5410059b04c1ee005303aed430f6e6645f61f4dc9e1461ec8f8916fdf18852c"
    "c09d07e0d55871f10f2a6d350d994acf6825a3cae70673d7def55616e6119dd7"]
   ["post-vanilla-query" "POST" "/" [["Param1" "value1"]] {"Host" "example.amazonaws.com"} signing/empty-payload-hash
    "28038455d6de14eafc1f9222cf5aa6f1a96197d7deb8263271d420d138af7f11"
    "a9ef26247f293bffe40eeff86a0480ba8c14d31503b2e0c3399e3a16bf1a3682"]
   ["post-x-www-form-urlencoded" "POST" "/" []
    {"Host" "example.amazonaws.com" "Content-Type" "application/x-www-form-urlencoded" "Content-Length" "13"} body-hash
    "d3875051da38690788ef43de4db0d8f280229d82040bfac253562e56c3f20e0b"
    "89a40deed0f26f9461242825a082d2222717248abc7ab41f552ad84a94ad46e9"]])

(deftest the-published-test-suite
  (doseq [[case-name method path query headers payload-hash header-signature query-signature] cases]
    (testing case-name
      (let [path      (signing/encode-for-uri path true)
            ;; a case that signs its body says so in an x-amz-content-sha256 header
            headers   (cond-> headers (= body-hash payload-hash) (assoc "X-Amz-Content-Sha256" payload-hash))
            signed    (signing/sign-request {:method method :path path :query query :headers headers :payload-hash payload-hash}
                                            credentials signed-at)]
        (is (= header-signature (second (re-find #"Signature=([0-9a-f]{64})$" (get signed "authorization")))))
        (is (str/starts-with? (get signed "authorization")
                              "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders="))
        (is (= "20150830T123600Z" (get signed "x-amz-date"))))
      (let [query-text (signing/presign-query {:method method :path (signing/encode-for-uri path true) :query query
                                               :headers (dissoc headers "X-Amz-Content-Sha256") :payload-hash payload-hash
                                               :expires-seconds 3600}
                                              credentials signed-at)]
        (is (= query-signature (second (re-find #"&X-Amz-Signature=([0-9a-f]{64})$" query-text))))))))

(deftest the-canonical-request-of-get-vanilla
  (is (= (str/join "\n" ["GET" "/" "" "host:example.amazonaws.com" "x-amz-date:20150830T123600Z" "" "host;x-amz-date"
                         "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"])
         (signing/make-canonical-request {:method "GET" :path "/" :query []
                                          :headers {"Host" "example.amazonaws.com" "X-Amz-Date" "20150830T123600Z"}
                                          :payload-hash signing/empty-payload-hash}))))
