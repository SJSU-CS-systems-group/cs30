#pragma once

#include <cstdio>
#include <istream>
#include <string>
#include <vector>

enum class Status {
    ok = 1,
    wrong_answer = 2,
    parsing_error = 3,
    error = 4,
};

#define ATTR_FORMAT(i) __attribute__ ((format (printf, i, i+1)))

struct Stream {
    bool strict;
    std::istream *file;
    bool close_file;
    bool hard_fail;
    std::string stream_name;

    void init(std::istream *file_, bool close_file_, std::string name_, bool strict_, bool hard_fail_=true);
    ~Stream();

    [[ noreturn ]] void quit(Status status, const char *message);
    ATTR_FORMAT(3) void quitf(Status status, const char *message, ...);
    ATTR_FORMAT(4) void quitif(bool cond, Status status, const char *message, ...);

    void readEoln();
    void readEof();

    char readChar();
    void readChar(char c);
    void readSpace();
    void skipWhitespace();

    std::string readWord();

    long long readLLong();
    long long readLLong(long long min_value, long long max_value, const std::string &name);
    
    std::vector<long long> readLLongs(int count);
    std::vector<long long> readLLongs(int count, long long min_value, long long max_value,
        const std::string &name, int idx_from=1);

    int readInt();
    int readInt(int min_value, int max_value, const std::string &name);
    
    std::vector<int> readInts(int count);
    std::vector<int> readInts(int count, int min_value, int max_value,
        const std::string &name, int idx_from=1);
};

void setupValidation();
void setupChecker(int argc, char *argv[]);
void finalizeValidation();

[[ noreturn ]] void quit(Status status, const char *message);
[[ noreturn ]] ATTR_FORMAT(2) void quitf(Status status, const char *message, ...);
ATTR_FORMAT(3) void quitif(bool cond, Status status, const char *message, ...);
ATTR_FORMAT(2) void ensuref(bool cond, const char *message, ...);

extern Stream inf, ansf, outf;

