n, p = map(int, input().split())
import time
time.sleep(1.5)  # deliberately slow but still correct — simulates an inefficient (not infinite) solution
ans = n + 1
prod = 1
while n:
    prod *= (n % p) + 1
    n //= p
ans -= prod
print(ans)
