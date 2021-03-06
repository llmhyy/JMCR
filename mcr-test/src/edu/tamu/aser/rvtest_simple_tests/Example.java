package edu.tamu.aser.rvtest_simple_tests;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;

import edu.tamu.aser.exploration.JUnit4MCRRunner;

@RunWith(JUnit4MCRRunner.class)
public class Example {

	private static int x, y;
	static int a,b;
	//private static Object lock = new Object();

	public static void main(String[] args) {
		Thread t1 = new Thread(new Runnable() {

			@Override
			public void run() {
				y = 1;
			}
		});
		
		Thread t2 = new Thread(new Runnable() {

			@Override
			public void run() {
				a = x;
				b = y;
			}
		});

		t1.start();
		t2.start();

		x = 1;

		try {
			t1.join();
			t2.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		System.out.println("a= " + a);
		System.out.println("b= " + b);
	}

	@Test
	public void test() throws InterruptedException {
		try {
			x = 0;
			y = 0;
//			a = 0;
//			b = 0;
		Example.main(null);
		} catch (Exception e) {
			System.out.println("here");
			fail();
		}
	}
}