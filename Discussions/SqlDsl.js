//TODO: Implement the following sql function as a tagged template. To address
//SQL injection, our function will escape any single quote (') character in
//values, i.e. replacing it with (\'). Not perfect, but proof-of-concept.
function sql(strings, ...values) {
  const fixed = values.map((v) => String(v).replaceAll("'", "\\'"));

  let res = "";
  // Works because guaranteed len(values) + 1 == strings.length
  for (let i = 0; i < strings.length; ++i) {
    res += strings[i];
    if (i < fixed.length) res += fixed[i];
  }
  return res;
}

function test(name, received, expected) {
  if (received == expected) {
    console.log(`${name}: Passed`);
  } else {
    console.log(`${name}: Failed`, { received });
  }
}

test(
  "literals",
  sql`
  SELECT * FROM users;
`,
  `
  SELECT * FROM users;
`
);

const name = "Robert";
test(
  "name",
  sql`
  SELECT * FROM users
  WHERE name = '${name}';
`,
  `
  SELECT * FROM users
  WHERE name = 'Robert';
`
);

const injection = "Robert'); DROP TABLE users;--";
test(
  "injection",
  sql`
  SELECT * FROM users
  WHERE name = '${injection}';
`,
  `
  SELECT * FROM users
  WHERE name = 'Robert\\'); DROP TABLE users;--';
`
);

const username = "username";
const password = "password' OR '1'='1";
test(
  "bypass",
  sql`
  SELECT * FROM users
  WHERE username = '${username}' AND password = '${password}'
`,
  `
  SELECT * FROM users
  WHERE username = 'username' AND password = 'password\\' OR \\'1\\'=\\'1'
`
);

const [one, two, three] = [1, 2, 3];
test("spacing", sql`${one}${two}${three}`, `123`);
