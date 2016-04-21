package com.jarvis.cache.redis;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import com.jarvis.cache.AbstractCacheManager;
import com.jarvis.cache.serializer.StringSerializer;
import com.jarvis.cache.to.AutoLoadConfig;
import com.jarvis.cache.to.CacheKeyTO;
import com.jarvis.cache.to.CacheWrapper;

/**
 * 缓存切面，用于拦截数据并调用Redis进行缓存
 * 
 * @author jiayu.qiu
 */
public class ShardedCachePointCut extends AbstractCacheManager {

	private static final Logger logger = Logger.getLogger(ShardedCachePointCut.class);

	private static final StringSerializer keySerializer = new StringSerializer();

	private JedisPool shardedJedisPool;

	/**
	 * Hash的缓存时长,默认值为0（永久缓存），设置此项大于0时，主要是为了防止一些已经不用的缓存占用内存。如果hashExpire
	 * 小于0则使用@Cache中设置的expire值。
	 */
	private int hashExpire = -1;

	/**
	 * 是否通过脚本来设置 Hash的缓存时长
	 */
	private boolean hashExpireByScript = false;

	public ShardedCachePointCut(AutoLoadConfig config) {
		super(config);
	}

	@Override
	public void setCache(CacheKeyTO cacheKeyTO, final CacheWrapper result) {
		if (null == shardedJedisPool || null == cacheKeyTO) {
			return;
		}
		String cacheKey = cacheKeyTO.getCacheKey();
		if (null == cacheKey || cacheKey.length() == 0) {
			return;
		}
		try {
			int expire = result.getExpire();
			Jedis jedis = shardedJedisPool.getResource();
			String hfield = cacheKeyTO.getHfield();
			if (null == hfield || hfield.length() == 0) {
				if (expire == 0) {
					jedis.set(keySerializer.serialize(cacheKey), getSerializer().serialize(result));
				} else {
					jedis.setex(keySerializer.serialize(cacheKey), expire, getSerializer().serialize(result));
				}
			} else {
				hashSet(jedis, cacheKey, hfield, result);
			}
		} catch (Exception ex) {
			logger.error(ex.getMessage(), ex);
		} finally {
			// returnResource(shardedJedis);
		}
	}

	private static byte[] hashSetScript;
	static {
		try {
			String tmpScript = "redis.call('HSET', KEYS[1], KEYS[2], ARGV[1]);\nredis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]));";
			hashSetScript = tmpScript.getBytes("UTF-8");
		} catch (UnsupportedEncodingException ex) {
			logger.error(ex.getMessage(), ex);
		}
	}

	private static final Map<Jedis, byte[]> hashSetScriptSha = new ConcurrentHashMap<Jedis, byte[]>();

	private void hashSet(Jedis jedis, String cacheKey, String hfield, CacheWrapper result) throws Exception {
		byte[] key = keySerializer.serialize(cacheKey);
		byte[] field = keySerializer.serialize(hfield);
		byte[] val = getSerializer().serialize(result);
		int hExpire;
		if (hashExpire < 0) {
			hExpire = result.getExpire();
		} else {
			hExpire = hashExpire;
		}
		if (hExpire == 0) {
			jedis.hset(key, field, val);
		} else {
			if (hashExpireByScript) {
				byte[] sha = hashSetScriptSha.get(jedis);
				if (null == sha) {
					sha = jedis.scriptLoad(hashSetScript);
					hashSetScriptSha.put(jedis, sha);
				}
				List<byte[]> keys = new ArrayList<byte[]>();
				keys.add(key);
				keys.add(field);

				List<byte[]> args = new ArrayList<byte[]>();
				args.add(val);
				args.add(keySerializer.serialize(String.valueOf(hExpire)));
				try {
					jedis.evalsha(sha, keys, args);
				} catch (Exception ex) {
					logger.error(ex.getMessage(), ex);
					try {
						sha = jedis.scriptLoad(hashSetScript);
						hashSetScriptSha.put(jedis, sha);
						jedis.evalsha(sha, keys, args);
					} catch (Exception ex1) {
						logger.error(ex1.getMessage(), ex1);
					}
				}
			} else {
				Pipeline p = jedis.pipelined();
				p.hset(key, field, val);
				p.expire(key, hExpire);
				p.sync();
			}
		}
	}

	@Override
	public CacheWrapper get(CacheKeyTO cacheKeyTO) {
		if (null == shardedJedisPool || null == cacheKeyTO) {
			return null;
		}
		String cacheKey = cacheKeyTO.getCacheKey();
		if (null == cacheKey || cacheKey.length() == 0) {
			return null;
		}
		CacheWrapper res = null;
		try {
			Jedis jedis = shardedJedisPool.getResource();
			byte bytes[] = null;
			String hfield = cacheKeyTO.getHfield();
			if (null == hfield || hfield.length() == 0) {
				bytes = jedis.get(keySerializer.serialize(cacheKey));
			} else {
				bytes = jedis.hget(keySerializer.serialize(cacheKey), keySerializer.serialize(hfield));
			}
			res = (CacheWrapper) getSerializer().deserialize(bytes);
		} catch (Exception ex) {
			logger.error(ex.getMessage(), ex);
		}
		return res;
	}

	/**
	 * 根据缓存Key删除缓存
	 * 
	 * @param cacheKeyTO
	 *            如果传进来的值中 带有 * 或 ? 号，则会使用批量删除（遍历所有Redis服务器）
	 */
	@Override
	public void delete(CacheKeyTO cacheKeyTO) {
		if (null == shardedJedisPool || null == cacheKeyTO) {
			return;
		}
		String cacheKey = cacheKeyTO.getCacheKey();
		if (null == cacheKey || cacheKey.length() == 0) {
			return;
		}
		logger.debug("delete cache:" + cacheKey);
		try {
			Jedis jedis = shardedJedisPool.getResource();
			if ("*".equals(cacheKey)) {
				jedis.flushDB();
			} else if (cacheKey.indexOf("*") != -1) {
				batchDel(jedis, cacheKey);
			} else {
				String hfield = cacheKeyTO.getHfield();
				if (null == hfield || hfield.length() == 0) {
					jedis.del(keySerializer.serialize(cacheKey));
				} else {
					jedis.hdel(keySerializer.serialize(cacheKey), keySerializer.serialize(hfield));
				}
				this.getAutoLoadHandler().resetAutoLoadLastLoadTime(cacheKeyTO);
			}
		} catch (Exception ex) {
			logger.error(ex.getMessage(), ex);
		} finally {
			// returnResource(shardedJedis);
		}
	}

	private static byte[] delScript;
	static {
		StringBuilder tmp = new StringBuilder();
		tmp.append("local keys = redis.call('keys', KEYS[1]);\n");
		tmp.append("if(not keys or #keys == 0) then \n return nil; \n end \n");
		tmp.append("redis.call('del', unpack(keys)); \n return keys;");
		try {
			delScript = tmp.toString().getBytes("UTF-8");
		} catch (UnsupportedEncodingException ex) {
			logger.error(ex.getMessage(), ex);
		}
	}

	private static final Map<Jedis, byte[]> delScriptSha = new ConcurrentHashMap<Jedis, byte[]>();

	private void batchDel(Jedis jedis, String cacheKey) throws Exception {
		// 如果是批量删除缓存，则要遍历所有redis，避免遗漏。
		byte[] sha = delScriptSha.get(jedis);
		byte[] key = keySerializer.serialize(cacheKey);
		if (null == sha) {
			sha = jedis.scriptLoad(delScript);
			delScriptSha.put(jedis, sha);
		}
		try {
			@SuppressWarnings("unchecked")
			List<String> keys = (List<String>) jedis.evalsha(sha, 1, key);
			if (null != keys && keys.size() > 0) {
				/*
				 * for(String tmpKey: keys) {
				 * autoLoadHandler.resetAutoLoadLastLoadTime(tmpKey); }
				 */
			}
		} catch (Exception ex) {
			logger.error(ex.getMessage(), ex);
			try {
				sha = jedis.scriptLoad(delScript);
				delScriptSha.put(jedis, sha);
				@SuppressWarnings("unchecked")
				List<String> keys = (List<String>) jedis.evalsha(sha, 1, key);
				if (null != keys && keys.size() > 0) {
					/*
					 * for(String tmpKey: keys) {
					 * autoLoadHandler.resetAutoLoadLastLoadTime(tmpKey); }
					 */
				}
			} catch (Exception ex1) {
				logger.error(ex1.getMessage(), ex1);
			}
		}
	}

	public int getHashExpire() {
		return hashExpire;
	}

	public void setHashExpire(int hashExpire) {
		if (hashExpire < 0) {
			return;
		}
		this.hashExpire = hashExpire;
	}

	public boolean isHashExpireByScript() {
		return hashExpireByScript;
	}

	public void setHashExpireByScript(boolean hashExpireByScript) {
		this.hashExpireByScript = hashExpireByScript;
	}
}
