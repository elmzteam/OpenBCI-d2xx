import java.util.Scanner;
import java.io.File;
import java.io.PrintWriter;

public class TestFeeder {
	private static StreamReader sr = new StreamReader();
	private static Scanner sc ;
	private static PrintWriter writer;

	public static void main(String[] args) {
		int index = 0;
		try {
			sc = new Scanner(new File("chan1.txt"));
			writer = new PrintWriter("dif_out.txt", "ascii"); 
		} catch (Exception e) {
			System.out.println("Fatal Error");
			return;
		}
		while(sc.hasNext()) {
			index++;
			sr.addFrame(sc.nextDouble());
			if (index % 50 == 0) {
				sr.scan();
			}
			writer.println(sr.getFrame(0));
		}
		writer.close();
	}
}
