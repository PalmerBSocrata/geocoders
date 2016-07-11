package com.socrata.geocoders

import java.io.IOException

import com.rojoma.json.v3.codec.JsonDecode.DecodeResult
import com.rojoma.json.v3.interpolation._
import com.rojoma.json.v3.io.JValueEventIterator
import com.rojoma.json.v3.ast.{JNull, JValue, JString}
import com.rojoma.json.v3.codec.{DecodeError, JsonDecode}
import com.rojoma.json.v3.util.{AutomaticJsonEncodeBuilder, JsonUtil, AutomaticJsonDecodeBuilder}
import com.socrata.http.client.exceptions.ContentTypeException
import com.socrata.http.client.{RequestBuilder, HttpClient}

class MapQuestGeocoder(http: HttpClient, appKey: String, metricProvider: (GeocodingResult, Long) => Unit, val retryCount: Int = 5) extends BaseGeocoder with RetryWithLogging {
  val provider = "MapQuest"
  val log = org.slf4j.LoggerFactory.getLogger(classOf[MapQuestGeocoder])

  override def batchSize = 100 // MapQuest (currently) supports batch geocoding of up to 100 locations

  override def geocode(addresses: Seq[InternationalAddress]): Seq[(Option[LatLon], JValue)] =
    addresses.grouped(batchSize).flatMap(geocodeBatch(metricProvider( _, _), _)).toVector

  private def cleanAddress(address: String): String =
    address.dropWhile(_ == '%') // work around mapquest bug

  private def encodeForMQ(addr: InternationalAddress): Map[String, String] = {
    val InternationalAddress(address, locality, subregion, region, postalCode, country) = addr
    val mb = Map.newBuilder[String, String]
    address.foreach { str => mb += "street" -> cleanAddress(str) }
    locality.foreach(mb += "adminArea5" -> _)
    subregion.foreach(mb += "adminArea4" -> _)
    region.foreach(mb += "adminArea3" -> _)
    postalCode.foreach(mb += "postalCode" -> _)
    mb += "adminArea1" -> country
    mb.result()
  }

  private sealed abstract class Granularity(val order: Int) extends Ordered[Granularity] {
    val level: Int
    def compare(that: Granularity): Int = // returns greater-than if this is more precise than that
      that.order compareTo this.order match { // lower numbers here mean more precise
        case 0 => this.level.compareTo(that.level) // higher numbers here mean more precise
        case other => other
      }
  }
  private case class GPoint(level: Int) extends Granularity(0)
  private case class GAddress(level: Int) extends Granularity(1)
  private case class GIntersection(level: Int) extends Granularity(2)
  private case class GStreet(level: Int) extends Granularity(3)
  private case class GPostalCode(level: Int) extends Granularity(4)
  private case class GAdminArea(level: Int) extends Granularity(5)

  private sealed trait Confidence
  private case object Exact extends Confidence
  private case object Good extends Confidence
  private case object Approximate extends Confidence
  private case object NoMeaningOrUnused extends Confidence

  private case class QualityCode(granularity: Granularity,
                                 fsnConfidence: Confidence,
                                 aaConfidence: Confidence,
                                 pcConfidence: Confidence,
                                 value: JString)
  private implicit val qcCodec = new JsonDecode[QualityCode] {
    val QC = "([PLIBAZ])([1-9])([ABCX])([ABCX])([ABCX])".r
    override def decode(x: JValue): DecodeResult[QualityCode] = x match {
      case str@JString(QC(granularity, subgranularity, c1, c2, c3)) =>
        def parseConfidence(c: String) = c match {
          case "A" => Exact
          case "B" => Good
          case "C" => Approximate
          case "X" => NoMeaningOrUnused
        }
        val sg = subgranularity.toInt
        val g = granularity match {
          case "P" => GPoint
          case "L" => GAddress
          case "I" => GIntersection
          case "B" => GStreet
          case "A" => GAdminArea
          case "Z" => GPostalCode
        }
        Right(QualityCode(g(sg), parseConfidence(c1), parseConfidence(c2), parseConfidence(c3), str))
      case s: JString =>
        Left(DecodeError.InvalidValue(s))
      case other =>
        Left(DecodeError.InvalidType(expected = JString, got = other.jsonType))
    }
  }

  private case class LatLng(lat: Double, lng: Double)
  private implicit val latLngCodec = AutomaticJsonDecodeBuilder[LatLng]

  private case class ResponseLocation(latLng: Option[LatLng], geocodeQualityCode: QualityCode) {
    def minAddressGranularity = GIntersection(1)
    def minLocalityGranularity = GAdminArea(5)
    def minSubregionGranularity = GAdminArea(4)
    def minRegionGranularity = GAdminArea(3)
    def minCountryGranularity = GAdminArea(1)

    def isAcceptable(addr: InternationalAddress): Boolean = {
      val InternationalAddress(address, locality, subregion, region, postalCode, _) = addr
      if(address.isDefined && (geocodeQualityCode.fsnConfidence == NoMeaningOrUnused || geocodeQualityCode.granularity < minAddressGranularity)) return false
      if(locality.isDefined && (geocodeQualityCode.aaConfidence == NoMeaningOrUnused || geocodeQualityCode.granularity < minLocalityGranularity)) return false
      if(subregion.isDefined && (geocodeQualityCode.aaConfidence == NoMeaningOrUnused || geocodeQualityCode.granularity < minSubregionGranularity)) return false
      if(region.isDefined && (geocodeQualityCode.aaConfidence == NoMeaningOrUnused || geocodeQualityCode.granularity < minRegionGranularity)) return false
      if(geocodeQualityCode.aaConfidence == NoMeaningOrUnused || geocodeQualityCode.granularity < minCountryGranularity) return false // country is always defined
      // zips are kind of special; we can (and indeed must) ignore them if we specified city or address
      // and the above checks passed.  In particular, this allows "Garden Grove, CA 92642" to pass.
      if(postalCode.isDefined && geocodeQualityCode.pcConfidence == NoMeaningOrUnused && !(address.isDefined || locality.isDefined)) return false
      true
    }
  }
  private implicit val responseLocationCodec = AutomaticJsonDecodeBuilder[ResponseLocation]

  private case class Result(locations: Seq[ResponseLocation])
  private implicit val resultCodec = AutomaticJsonDecodeBuilder[Result]

  private case class Response(results: Seq[Result])
  private implicit val responseCodec = AutomaticJsonDecodeBuilder[Response]

  private def geocodeBatch(metric: (GeocodingResult, Int) => Unit, addresses: Seq[InternationalAddress]): Seq[(Option[LatLon], JValue)] = {
    val converted = addresses.map(encodeForMQ)
    val requestBase = RequestBuilder("www.mapquestapi.com", secure = true).
      p("geocoding","v1","batch").
      q("key" -> appKey,
        "inFormat" -> "json",
        "outFormat" -> "json").
      timeoutMS(30000)

    val body = j"""{
        locations: $converted,
        options: { thumbMaps: false, maxResults: 1 }
      }"""
    retrying[Seq[(Option[LatLon], JValue)]] {
      def requestBody = JValueEventIterator(body)

      val start = System.nanoTime()
      http.execute(requestBase.json(requestBody)).run { resp =>
        resp.resultCode match {
          case 200 =>
            def logContentTypeFailure(v: =>JValue): JValue =
              try {
                v
              } catch {
                case e: ContentTypeException =>
                  log.warn("The response from MapQuest was not a valid JSON content type!  The body we sent was: {}", body)
                  throw e
              }

            JsonDecode.fromJValue[Response](logContentTypeFailure(resp.jValue())) match {
              case Right(geoResponse) =>
                val result = (geoResponse.results, addresses).zipped.map { (rl, addr) =>
                  val point = rl.locations.headOption match {
                    case Some(res) =>
                      (if (res.isAcceptable(addr)) res.latLng match {
                        case Some(latLng) => Some(LatLon(latLng.lat, latLng.lng)) // keep Mapquest's encoding internal
                        case None         => None
                      }
                      else None, res.geocodeQualityCode.value)
                    case None => (None, JNull)
                  }
                  if(point._1.isDefined) metric(SuccessResult, 1)
                  else metric(InsufficientlyPreciseResult, 1)
                  point
                }
                val end = System.nanoTime()
                log.info("Geocoded {} addresses in {}ms", result.length, (end-start) / 1000000)
                result
              case Left(e) =>
                metric(UninterpretableResult, 1)
                fail("Unable to interpret geocoding result from mapquest: " + e.english)
            }
          case 403 =>
            credentialsException("403 from upstream!  Is our token broken?")
          case other =>
            if(other == 500) {
              // "addresses" doesn't have an encoder, but to keep our logs clean we want to save it
              // as json (which will prevent random character stuff from being injected into the log
              // stream)
              implicit val encoder = AutomaticJsonEncodeBuilder[InternationalAddress]
              log.warn("500 from mapquest!  Going to retry, but just in case, here are the addresses we're trying to geocode: {}",
                JsonUtil.renderJson(addresses, pretty = false))
            }
            // force a retry
            throw new IOException(s"Unexpected result code $other from mapquest")
        }
      }
    }
  }
}
