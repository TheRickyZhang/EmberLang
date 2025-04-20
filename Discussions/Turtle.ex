defmodule Turtle do
  # Move a turtle and return the end position, starting at (0, 0) facing north (0, 1).
  # See assignment for a full list of instructions/requirements.
  def move(instructions),
    do: move(instructions, {0, 0}, {0, 1})

  # -------------- BEGIN NEW ---------------
  # Handle the wormhole case first
  defp move(instruction, {7, 7}, {dx, dy}),
    do: move(instruction, {-7, -7}, {-dx, -dy})

  defp move([{:left} | rest], position, {dx, dy}),
    do: move(rest, position, {-dy, dx})

  # Make jump instructions where jump {x, y} != {7, 7}
  defp move([{:jump, 7, 7} | _rest], _position, _direction),
    do: {:err, "invalid jump into wormhole (7, 7)"}

  defp move([{:jump, jx, jy} | rest], _position, direction),
    do: move(rest, {jx, jy}, direction)

  # -------------- END NEW -----------------

  # When instructions is empty, return the final result {:ok, position}.
  defp move([], position, _direction),
    do: {:ok, position}

  # When next instruction is specifically {:forward, 0}, then steps is invalid!
  defp move([{:forward, 0} | _rest], _position, _direction),
    do: {:err, "invalid forward steps (0)"}

  # When next instruction is {:forward, steps}, update position.
  defp move([{:forward, steps} | rest], {x, y}, {dx, dy}),
    do: move(rest, {x + dx * steps, y + dy * steps}, {dx, dy})

  # Match right or left.
  defp move([{:right} | rest], position, {dx, dy}),
    do: move(rest, position, {dy, -dx})

  # When next instruction is unknown (invalid), return an error result.
  defp move([instruction | _rest], _position, _direction),
    do: {:err, "invalid instruction #{inspect(instruction)}"}
end

instructions = [
  # {0, 1}
  {:forward, 1},
  # {0, 3}
  {:forward, 2}
]

IO.puts("Sample instructions & result")
IO.inspect(instructions, label: " - instructions")
IO.inspect(Turtle.move(instructions), label: " - result")

# Worth showing; values can be used in assignments like an assertion.
# However, the error message on failure doesn't include the value :/
{:ok, {0, 0}} = Turtle.move([])

test = fn name, expected, instructions ->
  case Turtle.move(instructions) do
    ^expected ->
      IO.puts(" - #{name}: passed!")

    received ->
      IO.puts(" - #{name}: expected #{inspect(expected)}, received #{inspect(received)}")
  end
end

IO.puts("\nRunning provided tests...")

test.("empty", {:ok, {0, 0}}, [
  # empty
])

test.("forward", {:ok, {0, 3}}, [
  # {0, 1}
  {:forward, 1},
  # {0, 3}
  {:forward, 2}
])

test.("forward zero steps", {:err, "invalid forward steps (0)"}, [
  # error!
  {:forward, 0}
])

test.("right", {:ok, {-1, 0}}, [
  # {2, 0}
  {:right},
  {:forward, 2},
  # {-1, 0}
  {:right},
  {:right},
  {:forward, 3}
])

test.("invalid instruction", {:err, "invalid instruction {:invalid}"}, [
  # error!
  {:invalid}
])

IO.puts("\nRunning additional tests...")

test.("left", {:ok, {5, 0}}, [
  # {-2, 0}
  {:left},
  {:forward, 2},
  # {1, 0}
  {:left},
  {:left},
  {:forward, 3},
  # {5, 0}
  {:left},
  {:right},
  {:forward, 4}
])

test.("jump", {:ok, {-3, 1}}, [
  # (1, 2)
  {:jump, 1, 2},
  # (-3, 1)
  {:jump, -3, -4},
  {:forward, 5}
])

test.("jump wormhole", {:err, "invalid jump into wormhole (7, 7)"}, [
  # error!
  {:jump, 7, 7}
])

test.("(7, 7) wormhole", {:ok, {-8, -7}}, [
  # {0, 7}
  {:forward, 7},
  # {-7, -7} (!)
  {:right},
  {:forward, 7},
  # {-8, -7} (!)
  {:forward, 1}
])
