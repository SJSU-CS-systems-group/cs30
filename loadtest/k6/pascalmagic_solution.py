n, p = map(int, input().split())
ans = n + 1
prod = 1
while n:
    prod *= (n % p) + 1
    n //= p
ans -= prod
print(ans)
