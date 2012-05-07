package com.am.jlfu.fileuploader.limiter;


import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.Validate;
import org.apache.commons.lang.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.am.jlfu.staticstate.entities.StaticStateRateConfiguration;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;



/**
 * @author Tomasz Nurkiewicz
 * @since 04.03.11, 22:08
 */
@Component
@ManagedResource(objectName = "JavaLargeFileUploader:name=rateLimiter")
public class RateLimiter
		implements TokenBucket {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);



	class RequestConfig {

		Semaphore semaphore;
		StaticStateRateConfiguration staticStateRateConfiguration;
		Long currentRateInBytes;

	}



	final LoadingCache<String, RequestConfig> requestConfigMap = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.DAYS)
			.build(new CacheLoader<String, RequestConfig>() {

				@Override
				public RequestConfig load(String arg0)
						throws Exception {
					log.trace("Created new bucket for #{}", arg0);
					RequestConfig requestConfig = new RequestConfig();
					requestConfig.semaphore = new Semaphore(0, false);
					requestConfig.staticStateRateConfiguration = new StaticStateRateConfiguration();
					return requestConfig;
				}
			});


	/** The size of the buffer in bytes */
	public static final int SIZE_OF_THE_BUFFER_FOR_A_TOKEN_PROCESSING_IN_BYTES = 1024;// 1KB

	/** The default request capacity. volatile because it can be changed. */
	private volatile int defaultRateInBytes = 100 * 1024 * SIZE_OF_THE_BUFFER_FOR_A_TOKEN_PROCESSING_IN_BYTES;
	// 100MB PER SECOND

	/** Number of times the bucket is filled per second. */
	public static final int NUMBER_OF_TIMES_THE_BUCKET_IS_FILLED_PER_SECOND = 10;



	@Scheduled(fixedRate = DateUtils.MILLIS_PER_SECOND / NUMBER_OF_TIMES_THE_BUCKET_IS_FILLED_PER_SECOND)
	/**
	 * This method releases from the per request map of buckets a number of semaphores depending of the rate limitation value 
	 */
	// TODO re-optimize
	// TODO master rate limitation (not just per request)
	public void fillBucket() {

		// calculate the maximum amount of semaphores to release which is gonna limit the rate.
		// basically: the rate we want divided by the amount of data a bucket represent and divided
		// by the number of times the bucket is filled (because
		// we pass in this method that often).

		// for all the requests we have
		for (Entry<String, RequestConfig> count : requestConfigMap.asMap().entrySet()) {

			// calculate from the rate in the config
			int requestBucketCapacity;
			Integer rateInKiloBytes = count.getValue().staticStateRateConfiguration.getRateInKiloBytes();
			if (rateInKiloBytes != null) {

				// check min
				if (rateInKiloBytes < 10) {
					rateInKiloBytes = 10;
				}

				// then calculate
				requestBucketCapacity = rateInKiloBytes * 1024 / SIZE_OF_THE_BUFFER_FOR_A_TOKEN_PROCESSING_IN_BYTES;

			}
			else {
				requestBucketCapacity = defaultRateInBytes / SIZE_OF_THE_BUFFER_FOR_A_TOKEN_PROCESSING_IN_BYTES;
			}

			// then calculate the maximum number to release;
			final int maxToRelease = requestBucketCapacity / NUMBER_OF_TIMES_THE_BUCKET_IS_FILLED_PER_SECOND;

			// release maximum minus number of semaphores still present
			final int releaseCount = maxToRelease - count.getValue().semaphore.availablePermits();

			// set the statistics
			final long stat =
					(long) releaseCount * NUMBER_OF_TIMES_THE_BUCKET_IS_FILLED_PER_SECOND * SIZE_OF_THE_BUFFER_FOR_A_TOKEN_PROCESSING_IN_BYTES;
			if (stat > 0) {
				count.getValue().currentRateInBytes = stat;
			}

			// if we have consumed some
			if (releaseCount > 0) {
				// release
				count.getValue().semaphore.release(releaseCount);
				log.trace("Releasing {} tokens for request #{}, available tokens: " + count.getValue().semaphore.availablePermits(), releaseCount,
						count.getKey());
			}
		}
	}


	@Override
	public boolean tryTake(String requestId) {
		return requestConfigMap.getUnchecked(requestId).semaphore.tryAcquire();
	}


	@Override
	public void completed(String requestId) {
		// reinit semaphore but do not clear configuration.
		requestConfigMap.getUnchecked(requestId).semaphore = new Semaphore(0, false);
		log.trace("Completed #{}, reinitializing semaphore", requestId);
	}


	@ManagedAttribute
	public int getEachBucketCapacity() {
		return defaultRateInBytes;
	}


	@ManagedAttribute
	public void setEachBucketCapacity(int eachBucketCapacity) {
		Validate.isTrue(eachBucketCapacity >= 0);
		this.defaultRateInBytes = eachBucketCapacity;
	}


	@ManagedAttribute
	public long getOngoingRequests() {
		return requestConfigMap.size();
	}


	public void assignConfigurationToRequest(String requestId, StaticStateRateConfiguration staticStateRateConfiguration) {
		requestConfigMap.getUnchecked(requestId).staticStateRateConfiguration = staticStateRateConfiguration;
	}


	public Long getUploadState(String requestIdentifier) {
		RequestConfig config = requestConfigMap.getUnchecked(requestIdentifier);
		return config.currentRateInBytes;
	}
}
