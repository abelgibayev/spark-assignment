import com.github.catalystcode.fortis.spark.streaming.rss._
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.{SaveMode, SparkSession, functions}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.feature.{HashingTF, Normalizer, Tokenizer}
import org.apache.spark.ml.classification.LinearSVC
import org.apache.spark.ml.evaluation.RegressionEvaluator


case class TrainingTweet(tweetId: Long, label: Integer, tweetContent: String)

object RSSDemo {

  val durationSeconds = 60 //update time

  var conf :SparkConf = _
  var sc  :SparkContext = _
  var ssc :StreamingContext = _
  var urlCSV :String = _ //our RSS link

  def init(): Unit ={
    conf = new SparkConf().setAppName("RSS Spark Application").setIfMissing("spark.master", "local[*]")
    sc = new SparkContext(conf)
    ssc = new StreamingContext(sc, Seconds(durationSeconds))
    sc.setLogLevel("ERROR")
  }
  def setRssUrl (url :String): Unit ={
    urlCSV = url
  }
  def getUrls(): Array[String] ={
    urlCSV.split(",")
  }
  def RSSToRDD(rssUrl :Array[String]): RSSInputDStream ={
    new RSSInputDStream(rssUrl, Map[String, String](
      "User-Agent" -> "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36"
    ), ssc, StorageLevel.MEMORY_ONLY, pollingPeriodInSeconds = durationSeconds)

  }

  def getLogisticRegression(): LogisticRegression ={
      new LogisticRegression()
        .setMaxIter(100)
        .setRegParam(0.01)
        .setElasticNetParam(0.5)
  }

  def getSVM(): LinearSVC ={
    new LinearSVC()
      .setMaxIter(10)
  }

  def normalizeData(): Unit ={

  }

  def main(args: Array[String]) {

    init()
    val testUrl = "https://queryfeed.net/twitter?q=putin&title-type=user-name-both&order-by=recent&geocode="
    setRssUrl(args(0))

    val urls = getUrls() //get url of each twit

    val tweetsFromRSS = RSSToRDD(urls) // RSS TO RDD

    val spark = SparkSession.builder().appName(sc.appName).getOrCreate() //create spark
    import spark.sqlContext.implicits._
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)


    var trainTweets = sqlContext.read.format("com.databricks.spark.csv") //train tweets for model to learn
      .option("header", "true")
      .option("inferSchema", "true")
      .load("train.csv").as[TrainingTweet] //adding train.csv
      .withColumn("tweetContent", functions.lower(functions.col("tweetContent")))


    val tokenizer = new Tokenizer()
      .setInputCol("tweetContent")
      .setOutputCol("words")

    val hashingTF = new HashingTF()
      .setNumFeatures(10000)
      .setInputCol(tokenizer.getOutputCol)
      .setOutputCol("features")

    val regression = getLogisticRegression() //


    val pipeline = new Pipeline()
      .setStages(
        Array(
          tokenizer,   // 1) tokenize
          hashingTF,   // 2) hashing
          regression)) // 3) making regression


    print(trainTweets.show())
    trainTweets = trainTweets
                .withColumn("tweetContent", functions.regexp_replace(
                     functions.col("tweetContent"),
                """[\p{Punct}&&[^.]]""", ""))


    //fitting model with preprocessing data
    val model = pipeline.fit(trainTweets)


    val labelColumn = "label"
    val evaluator = new RegressionEvaluator()
      .setLabelCol(labelColumn)
      .setPredictionCol(labelColumn)
      .setMetricName("rmse")


    tweetsFromRSS.foreachRDD(rdd => { //creating stream to get tweets
      if (!rdd.isEmpty()) {

        //from RDD to DS
        val tweets = rdd.toDS()
          .select("uri", "title")
          .withColumn("title", functions.lower(functions.col("title")))
          .withColumn("tweetId", functions.monotonically_increasing_id())
          .withColumn("tweetContent", functions.col("title"))

        //predict for DS
        val result = model.transform(tweets
          .withColumn("tweetContent", functions.regexp_replace(
            functions.col("tweetContent"),
            """[\p{Punct}&&[^.]]""", "")))
          .select("uri", "prediction", "probability")



        //printing result
        print(result.show())


        //save results to file
        result.toDF().write.mode(SaveMode.Append).save("output")
      }
    })

    // run forever
    ssc.start()
    ssc.awaitTermination()
  }
}
