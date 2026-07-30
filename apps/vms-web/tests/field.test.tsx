/**
 * US-06.2.1 — the Field contract: label association, error adjacency via aria-describedby,
 * and focus-first-invalid.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { Field, Input, Select, focusFirstInvalid } from "@/components/ui/Field";

afterEach(cleanup);

describe("Field", () => {
  it("associates the label with the control", () => {
    render(
      <Field label="Email">
        <Input type="email" />
      </Field>,
    );
    expect(screen.getByLabelText("Email")).toBeInstanceOf(HTMLInputElement);
  });

  it("renders the error adjacent and wires it through aria-describedby", () => {
    render(
      <Field label="Email" error="Enter a valid email address.">
        <Input type="email" />
      </Field>,
    );
    const input = screen.getByLabelText("Email");
    const error = screen.getByRole("alert");
    expect(error).toHaveTextContent("Enter a valid email address.");
    // The announcement carries the reason: describedby points at the error element.
    expect(input.getAttribute("aria-describedby")).toBe(error.id);
    expect(input).toHaveAttribute("aria-invalid", "true");
  });

  it("a valid control carries no invalid marker and no dangling describedby", () => {
    render(
      <Field label="Email">
        <Input type="email" />
      </Field>,
    );
    const input = screen.getByLabelText("Email");
    expect(input).not.toHaveAttribute("aria-invalid");
    expect(input).not.toHaveAttribute("aria-describedby");
  });

  it("hint and error are both referenced when both render", () => {
    render(
      <Field label="Username" hint="Lowercase letters only." error="Already taken.">
        <Input />
      </Field>,
    );
    const input = screen.getByLabelText("Username");
    const describedBy = input.getAttribute("aria-describedby") ?? "";
    const ids = describedBy.split(" ");
    expect(ids).toHaveLength(2);
    for (const id of ids) {
      expect(document.getElementById(id)).not.toBeNull();
    }
  });

  it("focusFirstInvalid focuses the first invalid control, in document order", () => {
    const { container } = render(
      <form>
        <Field label="Name">
          <Input />
        </Field>
        <Field label="Email" error="Required.">
          <Input type="email" />
        </Field>
        <Field label="Role" error="Pick one.">
          <Select>
            <option>TENANT</option>
          </Select>
        </Field>
      </form>,
    );

    expect(focusFirstInvalid(container)).toBe(true);
    expect(screen.getByLabelText("Email")).toHaveFocus();
  });

  it("focusFirstInvalid reports false and moves nothing when the form is clean", () => {
    const { container } = render(
      <Field label="Name">
        <Input />
      </Field>,
    );
    expect(focusFirstInvalid(container)).toBe(false);
  });
});
