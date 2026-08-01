package bm3.plasma;

/**
 * Payload targets used by the test suite. These live on the test classpath so the
 * bridge's reflective {@code Class.forName} / {@code newInstance} / {@code main}
 * dispatch can load them.
 */
public final class TestPayloads {
	private TestPayloads() {
	}

	public static final class TestRunnable implements Runnable {
		@Override
		public void run() {
			System.out.println("runnable-hello");
		}
	}

	public static final class SystemPropRunnable implements Runnable {
		@Override
		public void run() {
			System.out.println("prop=" + System.getProperty("plasma.test.prop", "unset"));
		}
	}

	public static final class EchoMain {
		public static void main(String[] args) {
			System.out.println("main:" + String.join(",", args));
		}
	}

	public static final class FailingRunnable implements Runnable {
		@Override
		public void run() {
			throw new IllegalStateException("boom");
		}
	}

	public static final class MultiLineRunnable implements Runnable {
		@Override
		public void run() {
			System.out.println("line-1");
			System.err.println("line-2");
		}
	}
}
