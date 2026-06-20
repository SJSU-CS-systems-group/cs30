# Baby Shark

s = input().lower().strip().split()
n = len(s)
for _ in range(n):
	s[_] = s[_].strip("?.:,!;")
maxword = s[0]
maxcount = 1
curcount = 1
for i in range(1,n):
	if s[i] == s[i-1]:
		curcount += 1
		if curcount > maxcount:
			maxword = s[i]
			maxcount = curcount
	else:
		curcount = 1
print(maxword)

