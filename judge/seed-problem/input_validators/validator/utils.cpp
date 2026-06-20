#include "utils.h"

#include <cassert>
#include <climits>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <istream>
#include <memory>
#include <string>
#include <vector>

using namespace std;

// TODO: line numbers of wa's

static char format_buffer[1<<20];
#define FORMAT_CALL() ({ \
    va_list a; va_start(a, message); \
    vsnprintf(format_buffer, sizeof format_buffer, message, a); \
    va_end(a); \
    quit(status, format_buffer); })

#define PARSE_VECTOR(v, func, ...) ({ \
    part_of_sequence = true; \
    for (int i=0; i<v.size(); ++i) { \
        res[i] = func(__VA_ARGS__); \
        sequence_idx++; \
        if (strict && i!=count-1) readSpace(); \
    } \
    part_of_sequence = false; })

bool isWhitespace(char c) {
    return c==' ' || c=='\t' || c=='\n';
}

void Stream::skipWhitespace() {
    while (isWhitespace(file->peek())) file->get();
}

std::string Stream::readWord() {
    if (!strict) skipWhitespace();

    int first = file->peek();
    if (first==EOF) quit(Status::parsing_error, "Unexpected EOF");
    if (isWhitespace(first)) quit(Status::parsing_error, "Unexpected whitespace");

    string ret = {(char)file->get()};

    while (1) {
        int cur = file->peek();
        if (cur==EOF || isWhitespace(cur)) break;

        ret += file->get();
    }
    return ret;
}

long long Stream::readLLong() {
    #define FAIL quitf(Status::parsing_error, "Invalid integer token '%s'", s.c_str())
    string s = readWord();
    if (s.empty()) FAIL;
    
    bool negative = s[0]=='-';
    if (negative && s.size()==1) FAIL;

    if (s[negative]=='0' && !(s.size()==1 && s[0]=='0')) FAIL;

    long long int ret = 0;
    for (int i=negative; i<s.size(); ++i) {
        if (!('0'<=s[i] && s[i]<='9')) FAIL;
        long long int a=ret;
        if (__builtin_mul_overflow(a, 10, &ret)) FAIL;
        a = ret;
        if (__builtin_add_overflow(a, s[i]-'0', &ret)) FAIL;
    }

    if (negative) ret*=-1;
    return ret;

    #undef FAIL
}

bool part_of_sequence = false;
int sequence_idx;

long long Stream::readLLong(long long min_value, long long max_value, const std::string &name) {
    long long ret = readLLong();
    if (ret<min_value || ret>max_value) {
        string actual_name = name;
        if (part_of_sequence) actual_name += "[" + to_string(sequence_idx) + "]";

        quit(Status::wrong_answer, ("Integer " + actual_name + "=" +
            to_string(ret) + " falls outside of range [" + to_string(min_value) + ", " +
            to_string(max_value) + "]").c_str());
    }
    return ret;
}

std::vector<long long> Stream::readLLongs(int count) {
    return readLLongs(count, LLONG_MIN, LLONG_MAX, "");
}

std::vector<long long> Stream::readLLongs(int count, long long min_value, long long max_value,
                        const std::string &name, int idx_from) {
    std::vector<long long> res(count);
    sequence_idx = idx_from;
    PARSE_VECTOR(res, readLLong, min_value, max_value, name);
    return res;
}

int Stream::readInt() {
    long long ret = readLLong();
    if (ret<INT_MIN || ret>INT_MAX)
        quitf(Status::parsing_error, "Expected 32 bit integer, got '%s'", to_string(ret).c_str());
    return ret;
}

int Stream::readInt(int min_value, int max_value, const std::string &name) {
    return (int) readLLong(min_value, max_value, name);
}

std::vector<int> Stream::readInts(int count) {
    return readInts(count, INT_MIN, INT_MAX, "");
}

std::vector<int> Stream::readInts(int count, int min_value, int max_value,
                        const std::string &name, int idx_from) {
    std::vector<int> res(count);
    sequence_idx = idx_from;
    PARSE_VECTOR(res, readInt, min_value, max_value, name);
    return res;
}

void Stream::readEoln() {
    int c = file->get();
    if (!strict && c==EOF) return;
    if (c=='\n') return;
    quit(Status::parsing_error, "Expected EOL");
}

void Stream::readEof() {
    if (file->get() != EOF) quit(Status::parsing_error, "Expected EOF");
}

char Stream::readChar() {
    return file->get();
}

void Stream::readChar(char c) {
    char ret = readChar();
    if (ret!=c) {
        string got = {ret};
        if (ret=='\n') got="\n";
        if (ret==EOF) got="EOF";
        quit(Status::parsing_error, ("Found '" + got + "' but expected '" + c + "'").c_str());
    }
}

void Stream::readSpace() {
    readChar(' ');
}

void Stream::init(std::istream *file_, bool close_file_, std::string name_, bool strict_, bool hard_fail_) {
    file=file_, close_file=close_file_, stream_name=name_, strict=strict_, hard_fail=hard_fail_;
}

Stream::~Stream() {
    if (close_file) delete file;
}

ostream *info_out;
void quit(Status status, const char *message) {
    if (status==Status::ok) {
        if (outf.file) {
            if (!outf.strict) outf.skipWhitespace();
            if (outf.file->get() != EOF)
                outf.quit(Status::parsing_error, "Extra information in the output");
        }
        *info_out<<message<<endl;
        exit(42);
    }

    if (status==Status::error) {
        *info_out<<"Error: "<<message<<endl;
        exit(1);
    }

    if (status==Status::parsing_error) *info_out<<"Wrong Answer(Parsing Error): "<<message<<endl;
    else if (status==Status::wrong_answer) *info_out<<"Wrong Answer: "<<message<<endl;
    exit(43);
}

void quitf(Status status, const char *message, ...) {
    FORMAT_CALL();
}

void quitif(bool cond, Status status, const char *message, ...) {
    if (cond) FORMAT_CALL();
}

void ensuref(bool cond, const char * message, ...) {
    Status status = Status::error;
    if (!cond) FORMAT_CALL();
}

void Stream::quit(Status status, const char *message) {
    if (hard_fail && status!=Status::ok) {
        *info_out << '[' << stream_name << "] ";
        status = Status::error;
    }
    ::quit(status, message);
}

void Stream::quitf(Status status, const char *message, ...) {
    FORMAT_CALL();
}

void Stream::quitif(bool cond, Status status, const char *message, ...) {
    if (cond) FORMAT_CALL();
}

Stream inf, ansf, outf;

void setupValidation() {
    inf.init(&cin, false, "Input", true);
    info_out = &cerr;
}

unique_ptr<ofstream> judgemessage_file;

void setupChecker(int argc, char *argv[]) {
    assert(argc >= 3);

    inf.init(new ifstream{argv[1]}, true, "Input", false);
    inf.close_file = true;

    ansf.init(new ifstream{argv[2]}, true, "Jury Answer", false);

    outf.init(&cin, false, "Output", false, false);

    judgemessage_file = make_unique<ofstream>(argv[3] + string{"judgemessage.txt"});
    info_out = judgemessage_file.get();
}

void finalizeValidation() {
    exit(42);
}

