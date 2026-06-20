#include "utils.h"
#include <string>
#include <cctype>

int main(int argc, char *argv[]) {
  setupValidation();

  // Read the entire line
  std::string line;
  line = inf.readWord();  // read the first word
  std::string rest_of_line;
  while (true) {
    int c = inf.file->peek();
    if (c == EOF || c == '\n') break;
    inf.readSpace();
    std::string word = inf.readWord();
    line += " " + word;
  }

  // Check line length
  ensuref(!line.empty(), "Input line must contain at least one word");
  ensuref(line.size() <= 80, "Input line exceeds 80 characters");

  // Check all characters
  for (char c : line) {
    ensuref(c == ' ' || ('a' <= c && c <= 'z'), 
        "Invalid character '%c' found in input", c);
  }

  inf.readEoln();
  inf.readEof();

  finalizeValidation();
}
