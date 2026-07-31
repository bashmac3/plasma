package bm3.plasma;

public class SampleTask implements Runnable {
    @Override
    public void run() {
        System.out.println("SampleTask executed from the local bridge");
    }
}
