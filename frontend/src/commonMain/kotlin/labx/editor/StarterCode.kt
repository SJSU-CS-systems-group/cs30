package labx.editor

val STARTER_CODE: Map<String, String> = linkedMapOf(
    "Kotlin" to """
        fun main() {
            val line = readLine()!!
            // Write your solution here
        }
    """.trimIndent() + "\n",

    "Java" to """
        import java.util.Scanner;

        public class Main {
            public static void main(String[] args) {
                Scanner sc = new Scanner(System.in);
                String line = sc.nextLine();
                // Write your solution here
            }
        }
    """.trimIndent() + "\n",

    "Python" to """
        def main():
            line = input()
            # Write your solution here

        if __name__ == "__main__":
            main()
    """.trimIndent() + "\n",

    "C++" to """
        #include <iostream>
        #include <string>
        using namespace std;

        int main() {
            string line;
            getline(cin, line);
            // Write your solution here
            return 0;
        }
    """.trimIndent() + "\n",
)

val LANGUAGES: List<String> = STARTER_CODE.keys.toList()

val DEFAULT_LANGUAGE: String = LANGUAGES.first()
