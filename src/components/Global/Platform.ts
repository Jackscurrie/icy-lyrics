// Spotify Types
type TokenProviderResponse = {
  accessToken: string;
  expiresAtTime: number;
  tokenType: "Bearer";
};

type AuthorizationState = {
  isAuthorized?: boolean;
  token?: {
    accessToken?: string;
    accessTokenExpirationTimestampMs?: number;
    tokenType?: string;
    isAnonymous?: boolean;
  } | null;
};

// Store all our Spotify Services
const Spotify: typeof Spicetify = (globalThis as any).Spicetify;
let SpotifyPlatform: typeof Spicetify.Platform;
let SpotifyInternalFetch: typeof Spicetify.CosmosAsync;

// Spotify Ready Promise
const OnSpotifyReady = new Promise<void>((resolve) => {
  const CheckForServices = () => {
    SpotifyPlatform = Spotify.Platform;
    SpotifyInternalFetch = Spotify.CosmosAsync;

    if (!SpotifyPlatform || !SpotifyInternalFetch) {
      requestAnimationFrame(() => setTimeout(CheckForServices, 0));
      return;
    }

    resolve();
  };

  CheckForServices();
});

// Get Spotify Access Token Function
let tokenProviderResponse: TokenProviderResponse | undefined;
let accessTokenPromise: Promise<string> | undefined;

const TOKEN_EXPIRY_MARGIN_MS = 2;

function isUsable(response: TokenProviderResponse | undefined): response is TokenProviderResponse {
  if (!response?.accessToken) return false;
  if (typeof response.expiresAtTime !== "number" || !Number.isFinite(response.expiresAtTime)) {
    return true;
  }
  return response.expiresAtTime - Date.now() > TOKEN_EXPIRY_MARGIN_MS;
}

function tokenFromAuthorizationAPI(): TokenProviderResponse | undefined {
  try {
    const api = (SpotifyPlatform as any)?.AuthorizationAPI;
    if (typeof api?.getState !== "function") return undefined;

    const state: AuthorizationState = api.getState();
    const token = state?.token;
    if (!token?.accessToken || state.isAuthorized === false) return undefined;

    return {
      accessToken: token.accessToken,
      expiresAtTime: token.accessTokenExpirationTimestampMs as number,
      tokenType: "Bearer",
    };
  } catch (error) {
    console.warn("AuthorizationAPI.getState() failed, falling back", error);
    return undefined;
  }
}

async function tokenFromLegacySources(): Promise<TokenProviderResponse | undefined> {
  try {
    const result: TokenProviderResponse = await SpotifyInternalFetch.get("sp://oauth/v2/token");
    if (result?.accessToken) {
      return {
        accessToken: result.accessToken,
        expiresAtTime: result.expiresAtTime,
        tokenType: "Bearer",
      };
    }
  } catch (error) {
    console.warn("sp://oauth/v2/token failed, falling back to Platform.Session", error);
  }

  const session = (SpotifyPlatform as any)?.Session;
  if (!session?.accessToken) {
    console.warn("Failed to find SpotifyPlatform.Session for fetching token");
    return undefined;
  }

  return {
    accessToken: session.accessToken,
    expiresAtTime: session.accessTokenExpirationTimestampMs,
    tokenType: "Bearer",
  };
}

async function resolveAccessToken(): Promise<string> {
  await OnSpotifyReady;

  const response = tokenFromAuthorizationAPI() ?? (await tokenFromLegacySources());
  if (!response?.accessToken) throw new Error("Unable to obtain a Spotify access token");

  tokenProviderResponse = response;
  return response.accessToken;
}

const GetSpotifyAccessToken = (): Promise<string> => {
  if (isUsable(tokenProviderResponse)) {
    return Promise.resolve(tokenProviderResponse.accessToken);
  }

  tokenProviderResponse = undefined;
  if (accessTokenPromise) return accessTokenPromise;

  const pending = resolveAccessToken().finally(() => {
    if (accessTokenPromise === pending) accessTokenPromise = undefined;
  });
  accessTokenPromise = pending;
  return pending;
};

const Platform = {
  OnSpotifyReady,
  GetSpotifyAccessToken,
  get SpotifyVersion(): number[] {
    return Spicetify.Platform.version.split(".").map((i) => Number.parseInt(i, 10));
  }
};

export default Platform;
