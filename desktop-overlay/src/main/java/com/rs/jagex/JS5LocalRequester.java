package com.rs.jagex;

public class JS5LocalRequester implements Runnable {

	Queue aClass477_3664 = new Queue();
	int anInt3662;
	volatile boolean aBool3661;
	private int activeIo;
	private boolean quiescing;
	private long quiesceGeneration = -1;
	Thread aThread3663 = new Thread(this);

	public JS5LocalRequester() {
		aThread3663.setDaemon(true);
		aThread3663.start();
		aThread3663.setPriority(1);
	}

	/** Admission closes immediately; accepted requests continue draining on the existing worker. */
	public boolean beginQuiesce(long generation) {
		synchronized (aClass477_3664) {
			if (aBool3661 || generation < 0) return false;
			if (quiescing) return generation == quiesceGeneration;
			if (generation <= quiesceGeneration) return false;
			quiesceGeneration = generation;
			quiescing = true;
			aClass477_3664.notifyAll();
			return true;
		}
	}

	public boolean isQuiescent(long generation) {
		synchronized (aClass477_3664) {
			return quiescing && generation == quiesceGeneration && anInt3662 == 0 && activeIo == 0;
		}
	}

	public boolean resume(long generation) {
		synchronized (aClass477_3664) {
			if (aBool3661 || !quiescing || generation != quiesceGeneration) return false;
			quiescing = false;
			aClass477_3664.notifyAll();
			return true;
		}
	}

	private void requireAdmission() {
		if (quiescing || aBool3661)
			throw new java.util.concurrent.RejectedExecutionException("Local cache admission is closed");
	}

	CacheableNode_Sub11_Sub2 method5564(int i_1, byte[] bytes_2, JS5CacheFile js5cachefile_3) {
		CacheableNode_Sub11_Sub2 class282_sub50_sub11_sub2_5 = new CacheableNode_Sub11_Sub2();
		class282_sub50_sub11_sub2_5.anInt10467 = 2;
		class282_sub50_sub11_sub2_5.key = i_1;
		class282_sub50_sub11_sub2_5.aByteArray10469 = bytes_2;
		class282_sub50_sub11_sub2_5.aClass203_10464 = js5cachefile_3;
		class282_sub50_sub11_sub2_5.highPriority = false;
		method5569(class282_sub50_sub11_sub2_5);
		return class282_sub50_sub11_sub2_5;
	}

	public void method5565() {
		aBool3661 = true;
		Queue class477_2 = aClass477_3664;
		synchronized (aClass477_3664) {
			aClass477_3664.notifyAll();
		}

		try {
			aThread3663.join();
		} catch (InterruptedException ignored) {
		}

		aThread3663 = null;
	}

	CacheableNode_Sub11_Sub2 method5567(int i_1, JS5CacheFile js5cachefile_2) {
		CacheableNode_Sub11_Sub2 class282_sub50_sub11_sub2_4 = new CacheableNode_Sub11_Sub2();
		class282_sub50_sub11_sub2_4.anInt10467 = 3;
		class282_sub50_sub11_sub2_4.key = i_1;
		class282_sub50_sub11_sub2_4.aClass203_10464 = js5cachefile_2;
		class282_sub50_sub11_sub2_4.highPriority = false;
		method5569(class282_sub50_sub11_sub2_4);
		return class282_sub50_sub11_sub2_4;
	}

	void method5569(CacheableNode_Sub11_Sub2 class282_sub50_sub11_sub2_1) {
		Queue class477_3 = aClass477_3664;
		synchronized (aClass477_3664) {
			requireAdmission();
			aClass477_3664.add(class282_sub50_sub11_sub2_1);
			++anInt3662;
			aClass477_3664.notifyAll();
		}
	}

	CacheableNode_Sub11_Sub2 method5578(int i_1, JS5CacheFile js5cachefile_2) {
		CacheableNode_Sub11_Sub2 class282_sub50_sub11_sub2_4 = new CacheableNode_Sub11_Sub2();
		class282_sub50_sub11_sub2_4.anInt10467 = 1;
		Queue class477_5 = aClass477_3664;
		synchronized (aClass477_3664) {
			requireAdmission();
			CacheableNode_Sub11_Sub2 class282_sub50_sub11_sub2_6 = (CacheableNode_Sub11_Sub2) aClass477_3664.method7941();

			while (class282_sub50_sub11_sub2_6 != null) {

				if (i_1 == class282_sub50_sub11_sub2_6.key && js5cachefile_2 == class282_sub50_sub11_sub2_6.aClass203_10464 && class282_sub50_sub11_sub2_6.anInt10467 == 2) {
					class282_sub50_sub11_sub2_4.aByteArray10469 = class282_sub50_sub11_sub2_6.aByteArray10469;
					class282_sub50_sub11_sub2_4.waiting = false;
					return class282_sub50_sub11_sub2_4;
				}

				class282_sub50_sub11_sub2_6 = (CacheableNode_Sub11_Sub2) aClass477_3664.method7955();
			}
			++activeIo; // Covers synchronous reads and the admission-to-I/O gap.
		}

		try {
			class282_sub50_sub11_sub2_4.aByteArray10469 = js5cachefile_2.method3350(i_1);
			class282_sub50_sub11_sub2_4.highPriority = true;
			return class282_sub50_sub11_sub2_4;
		} finally {
			class282_sub50_sub11_sub2_4.waiting = false;
			synchronized (aClass477_3664) { --activeIo; aClass477_3664.notifyAll(); }
		}
	}

	@Override
	public void run() {
		while (!aBool3661) {
			Queue class477_2 = aClass477_3664;
			CacheableNode_Sub11_Sub2 class282_sub50_sub11_sub2_1;
			synchronized (aClass477_3664) {
				class282_sub50_sub11_sub2_1 = (CacheableNode_Sub11_Sub2) aClass477_3664.method7937();
				if (class282_sub50_sub11_sub2_1 == null) {
					try {
						aClass477_3664.wait();
					} catch (InterruptedException ignored) {
					}
					continue;
				}

				--anInt3662;
				++activeIo; // Atomic with dequeue: an empty queue alone is never the drain barrier.
			}

			try {
				if (class282_sub50_sub11_sub2_1.anInt10467 == 2)
					class282_sub50_sub11_sub2_1.aClass203_10464.method3348((int) class282_sub50_sub11_sub2_1.key, class282_sub50_sub11_sub2_1.aByteArray10469, class282_sub50_sub11_sub2_1.aByteArray10469.length);
				else if (class282_sub50_sub11_sub2_1.anInt10467 == 3)
					class282_sub50_sub11_sub2_1.aByteArray10469 = class282_sub50_sub11_sub2_1.aClass203_10464.method3350((int) class282_sub50_sub11_sub2_1.key);
			} catch (Exception exception_4) {
				Class151.method2594(null, exception_4);
			} finally {
				class282_sub50_sub11_sub2_1.waiting = false;
				synchronized (aClass477_3664) { --activeIo; aClass477_3664.notifyAll(); }
			}
		}

	}

}
